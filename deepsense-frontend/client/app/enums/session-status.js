'use strict';

exports.inject = function(module) {
  module.constant('SessionStatus', {
    NOT_RUNNING: 'not_running',
    STARTING: 'starting',
    RUNNING: 'running',
    RUNNING_AND_READY: 'running_and_ready'
  });
};
