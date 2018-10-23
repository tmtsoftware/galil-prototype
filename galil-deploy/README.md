# galil-deploy

This module contains apps and configuraiton files for host deployment using 
HostConfig (https://tmtsoftware.github.io/csw/apps/hostconfig.html) and 
ContainerCmd (https://tmtsoftware.github.io/csw/framework/deploying-components.html).

An important part of making this work is ensuring the host config app (GalilHostConfigApp) is built
with all of the necessary dependencies of the components it may run.  This was done by adding settings to the
built.sbt file:

```
lazy val `galil-deploy` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`,
  ))
  .dependsOn(`galil-assembly`, `galil-hcd`)
  ```

and in Dependencies.scala:

```

  val `csw-framework`  = "org.tmt" %% "csw-framework"  % Version

```

To use the Galil Assembly and HCD artifacts, sbt publishLocal should be used on those modules (or root).

Then, sbt universal:publishLocal stage should be used to build this project.  This will put the galil-host-config-app 
and galil-container-cmd-app in ${PROJECT}/target/universal/stage/bin.  From there, you can run the deploy app like:

`./galil-host-config-app --local ../../../../galil-deploy/src/main/resources/GalilPrototypeHostConfig.conf -s ./galil-container-cmd-app`

Note: The CSW services need to be running before running the Galil applications (For example, run: `csw-services.sh start`).
