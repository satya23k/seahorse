/**
 * Copyright (c) 2015, CodiLime, Inc.
 *
 * Owner: Grzegorz Chilkiewicz
 */
package io.deepsense.graphexecutor

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.Executors
import java.util.{List, UUID}

import scala.collection.mutable

import com.google.inject.name.Names
import com.google.inject.{Guice, Key}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.ipc.NettyServer
import org.apache.avro.ipc.specific.SpecificResponder
import org.apache.avro.specific.SpecificData
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync
import org.apache.hadoop.yarn.client.api.async.impl.AMRMClientAsyncImpl
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.spark.sql.SQLContext
import org.apache.spark.{SparkConf, SparkContext}

import io.deepsense.deeplang.doperables.dataframe.DataFrameBuilder
import io.deepsense.commons.config.ConfigModule
import io.deepsense.deeplang.{DOperable, ExecutionContext}
import io.deepsense.entitystorage.{EntityStorageClient, EntityStorageClientFactory}
import io.deepsense.graph.{Graph, Status}
import io.deepsense.graphexecutor.protocol.GraphExecutorAvroRpcProtocol
import io.deepsense.graphexecutor.util.BinarySemaphore
import io.deepsense.models.experiments.Experiment

object GraphExecutor {
  var entityStorageClientFactory: EntityStorageClientFactory = _

  def main(args: Array[String]): Unit = {
    val injector = Guice.createInjector(
      new ConfigModule,
      new GraphExecutorModule,
      new GraphExecutorTestModule)
    val entityStorageClientFactory = injector.getInstance(
      Key.get(classOf[EntityStorageClientFactory], Names.named(args(0))))
    val graphExecutor = new GraphExecutor(entityStorageClientFactory)
    graphExecutor.mainLoop()
  }
}

/**
 * GraphExecutor (it is YARN asynchronous Application Master).
 * Starts Avro RPC server, waits for graph and performs its execution.
 * Only one graph execution per GraphExecutor can be performed.
 */
class GraphExecutor(entityStorageClientFactory: EntityStorageClientFactory)
  extends AMRMClientAsync.CallbackHandler
  with LazyLogging {

  implicit val conf: YarnConfiguration = new YarnConfiguration()

  val dOperableCache = mutable.Map[UUID, DOperable]()

  val entityStorageClient = createEntityStorageClient(entityStorageClientFactory)

  /** graphGuard is used to prevent concurrent graph field modifications/inconsistent reads */
  val graphGuard = new Object()

  @volatile var experiment: Option[Experiment] = None

  def graph: Option[Graph] = experiment.map(_.graph)

  /**
   * graphEventBinarySemaphore is used for signaling important changes in graph object
   * (graph sent, finished or aborted node execution)
   */
  val graphEventBinarySemaphore = new BinarySemaphore(0)

  /** executorsPool is pool of threads executing GraphNodes */
  val executorsPool = Executors.newFixedThreadPool(Constants.ConcurrentGraphNodeExecutors)

  def mainLoop(): Unit = {
    // TODO: don't print anything
    println("mainLoop() start" + System.currentTimeMillis)
    val resourceManagerClient = new AMRMClientAsyncImpl[ContainerRequest](
      Constants.AMRMClientHeartbeatInterval,
      this)
    resourceManagerClient.init(conf)
    resourceManagerClient.start()

    // Construction of server for communication with ExperimentManager
    val rpcServer = new NettyServer(new SpecificResponder(
      classOf[GraphExecutorAvroRpcProtocol],
      new GraphExecutorAvroRpcImpl(this),
      new SpecificData()),
      new InetSocketAddress(0)
    )

    // Application Master registration
    val appMasterHostname = InetAddress.getLocalHost.getHostName
    resourceManagerClient.registerApplicationMaster(appMasterHostname, rpcServer.getPort, "")


    // TODO: don't print anything
    println("waitForGraph start " + System.currentTimeMillis)
    // Wait for graph with specified timeout and if graph has been sent, proceed with its execution.
    if (this.waitForGraph(timeout = Constants.WaitingForGraphDelay)) {
      // TODO: don't print anything
      println("waitForGraph succeeded " + System.currentTimeMillis)
      // All nodes are marked as queued (there is no partial execution).
      graphGuard.synchronized {
        experiment = Some(experiment.get
          .copy(graph = Graph(graph.get.nodes.map(_.markQueued), graph.get.edges)))
      }
      val executionContext = new ExecutionContext()
      // Acquire Spark Context
      val sparkConf = new SparkConf()
      sparkConf.setAppName("Spark DeepSense Akka PoC")
      sparkConf.set("spark.executor.memory", "512m")
      val sparkContext = new SparkContext(sparkConf)

      executionContext.sqlContext = new SQLContext(sparkContext)
      executionContext.dataFrameBuilder = DataFrameBuilder(executionContext.sqlContext)
      executionContext.entityStorageClient = entityStorageClient
      graphGuard.synchronized {
        executionContext.tenantId = experiment.get.tenantId
      }


      // Loop until there are nodes in graph that are ready to or during execution
      while (graphGuard.synchronized {
        graph.get.readyNodes.nonEmpty ||
          graph.get.nodes.filter(n => n.state.status == Status.Running).nonEmpty
      }) {
        // Retrieve list of nodes ready for execution in synchronized manner and use this list:
        // Put its elements into RUNNING status and schedule their execution in executorPool.
        graphGuard.synchronized {
          for (node <- graph.get.readyNodes) {
            // Synchronization guarantees that this node is still ready for execution
            experiment = Some(experiment.get
              .copy(graph = graph.get.markAsRunning(node.id)))
            // executorPool won't throw RejectedExecutionException thanks to comprehensive
            // synchronization in GraphExecutorAvroRpcImpl.terminateExecution().
            // NOTE: Threads executing graph nodes are created in main thread for thread-safety.
            executorsPool.execute(
              new GraphNodeExecutor(
                executionContext,
                this,
                graph.get.node(node.id),
                entityStorageClient))
          }
        }
        // Waiting for change in graph (completed or failed nodes).
        // NOTE: Active waiting is performed to increase immunity to deadlock problems
        // (it won't have substantial impact on performance, but may prevent some
        // problems in distributed cluster environment).
        try {
          graphEventBinarySemaphore.tryAcquire(Constants.WaitingForNodeExecutionInterval)
        } catch {
          case e: InterruptedException => {
            // Silently proceed to next loop iteration
          }
        }
      }

      // Mark as aborted all nodes that Graph Executor wasn't able to execute
      graphGuard.synchronized {
        if (graph.get.nodes.forall(_.isCompleted)) {
          experiment = Some(experiment.get.markCompleted)
        } else {
          if (graph.get.nodes.exists(_.isFailed)) {
            experiment = Some(experiment.get.markFailed("Not all nodes were executed"))
          } else {
            experiment = Some(experiment.get.markAborted)
          }
        }
      }
    }

    cleanup(resourceManagerClient, rpcServer, entityStorageClientFactory)
  }

  /**
   * Waits specified timeout (time in ms) for receiving graph designated to immediate execution.
   * NOTE: System time change during waiting for graph may result in unexpected behaviour.
   * @param timeout timeout to wait (time in ms)
   * @return true if graph has been received in specified timeout and false otherwise
   */
  private def waitForGraph(timeout: Int): Boolean = {
    val startOfWaitingForGraphMs = System.currentTimeMillis
    while (graph.isEmpty &&
      System.currentTimeMillis - startOfWaitingForGraphMs < timeout) {
      val remainingTimeToWait = timeout - (System.currentTimeMillis - startOfWaitingForGraphMs)
      try {
        graphEventBinarySemaphore.tryAcquire(remainingTimeToWait)
      } catch {
        case e: InterruptedException => {
          // Silently proceed to next loop iteration
        }
      }
    }
    graph.nonEmpty
  }

  /**
   * Performs orderly closing of Graph Executor components:
   * Application Master unregisteration, RPC server closing and executor thread pool shutdown.
   * @param rmClient YARN Resource Manager client
   * @param rpcServer Avro RPC server
   */
  private def cleanup(
      rmClient: AMRMClientAsyncImpl[ContainerRequest],
      rpcServer: NettyServer,
      entityStorageClientFactory: EntityStorageClientFactory): Unit = {
    // TODO: don't print anything
    println("cleanup start" + System.currentTimeMillis)
    rmClient.unregisterApplicationMaster(FinalApplicationStatus.SUCCEEDED, "", "")
    // Netty problem identified:
    // ClosedChannelException is thrown in client connection thread when closing server during
    // not completed client connection.
    // Can be easily observed when all delays are removed, WaitingForGraphCheckIntervalMs is low
    // and server closing is swapped with Graph Executor unregistering.
    // https://github.com/netty/netty/issues/2350
    // https://linkedin.jira.com/browse/NOR-19
    // Avro 1.7.7 uses: Netty 3.4.0
    // http://mvnrepository.com/artifact/org.apache.avro/avro-ipc/1.7.7
    //
    // Current workaround:
    // Put few seconds delay between Graph Executor unregistration
    // (which indicates clients to avoid future communication with Graph Executor)
    // and AVRO RPC server closing to make sure that no client will try to connect
    // to server during server closing
    Thread.sleep(Constants.UnregisteringAndClosingRpcServerDelay)
    rpcServer.close()
    executorsPool.shutdown()
    // TODO: don't print anything
    entityStorageClientFactory.close()
    println("cleanup end" + System.currentTimeMillis)
  }


  override def onContainersCompleted(statuses: List[ContainerStatus]): Unit = {}

  override def onError(e: Throwable): Unit = {}

  /**
   * Returns approximation of overall progress of an ApplicationMaster;
   * Approximation is rough and probably won't be useful to anyone except cluster maintainer.
   * This function embrace three ideas:
   * 1) no completed/running nodes doesn't mean no work has been done,
   * 2) currently running nodes count as half-done,
   * 3) all nodes completed doesn't mean no further work is needed.
   * @return Approximation; float in <0.0f, 1.0f>
   */
  override def getProgress: Float = {
    graphGuard.synchronized {
      graph match {
        case Some(graph) =>
          val allNodes = graph.nodes
          val allCount = allNodes.size
          val startedCount = allNodes.count(n => n.state.started.nonEmpty && n.state.ended.isEmpty)
          val endedCount = allNodes.count(n => n.state.ended.nonEmpty)
          (0.5f + (startedCount * 0.5f) + endedCount) / (allCount + 1.0f)
        // NOTE: If graph has not been received yet, assume 1% progress
        case None => 0.01f
      }
    }
  }

  override def onShutdownRequest(): Unit = {}

  override def onNodesUpdated(updatedNodes: List[NodeReport]): Unit = {}

  override def onContainersAllocated(containers: List[Container]): Unit = {}

  private def createEntityStorageClient(entityStorageClientFactory: EntityStorageClientFactory)
      : EntityStorageClient = {
    val config = ConfigFactory.load(Constants.GraphExecutorConfigName)
    val actorSystemName = config.getString("entityStorage.actorSystemName")
    val hostName = config.getString("entityStorage.hostname")
    val port = config.getInt("entityStorage.port")
    val actorName = config.getString("entityStorage.actorName")
    val timeoutSeconds = config.getInt("entityStorage.timeoutSeconds")
    logger.info(
      s"EntityStorageClient($actorSystemName, $hostName, $port, $actorName, $timeoutSeconds)")
    entityStorageClientFactory.create(actorSystemName, hostName, port, actorName, timeoutSeconds)
  }
}