# Galil Client

This project currently contains some demo classes as examples of locating and communicating with A Galil HCD.

* GalilAssemblyClient: Demo class that looks up the Galil assembly and sends it a command 

* GalilHcdClient: Provides a simple client API to the Galil HCD 

* GalilHcdClientApp: Demo showing GalilHcdClient usage (Could be further developed into a command line app for sending commands to the Galil HCD...)

* **TrackInjectorApp**: Standalone PVT tracking injector for lab testing. Plays
  the role of the K-Mirror Assembly by streaming `trackAxis(axis, position,
  rate, validTime)` commands at a configurable cadence. Supports `--shape
  constant` (constant velocity) and `--shape sinusoid` (sinusoidal position
  trajectory). The `--lead-margin` parameter controls FIFO slack beyond the
  cadence period — at 1 Hz cadence with `--lead-margin 0.2`, each segment's
  `validTime` is 1.2 s in the future. Resolves the target HCD by component
  name (`--hcd-component`, default `ICS.HCD.GalilMotion`); pass
  `ICS.HCD.GalilMotion.1` for the STB instance, `.0` for simulator, etc. Run
  with `sbt "galil-client/runMain csw.proto.galil.client.TrackInjectorApp ..."`.
  Ctrl-C cleanly submits `stopAxis` before terminating.