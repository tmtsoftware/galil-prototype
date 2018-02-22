# Galil HCD

This project implements a Galil HCD that accepts _Setup_ or _OneWay_ commands with names that are configured in [GalilCommands.conf](../galil-commands/resources/GalilCommands.conf).

The HCD itself is configured in [resources/GalilHcd.conf](resources/GalilHcd.conf).

See the [csw-prod documentation](https://tmtsoftware.github.io/csw-prod/) for how HCDs are defined and used.

The Galil HCD assumes that either the galil-simulator is running or that you have a Galil device connected to your network.
To use a local device, either edit resources/application.conf:

```
galil {
  host = "galil-host-or-ip"
  port = 23
}
```

replacing host and port with the correct values for the device,
or run your application with options:

```
-Dgalil.host="galil-host-or-ip" -Dgalil.port=23
```
