// Copyright (c) 2015, CodiLime, Inc.
//
// Owner: Rafał Hryciuk

name := "deepsense-entitystorage"

libraryDependencies ++= Dependencies.entitystorage

unmanagedClasspath in Runtime += (baseDirectory.value / "conf")

unmanagedClasspath in Compile += (baseDirectory.value / "conf")

Revolver.settings