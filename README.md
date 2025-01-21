# Parental Controls

Adds a server-side per-player time limit.

![Icon featuring a clock and a shield icon](src/main/resources/assets/parentalcontrols/icon.png)

## Features

- Configurable time limit, kick message, etc.
- Tick-based timer (lag spikes won't count toward elapsed time)
- Reset at midnight

## Usage

The default time limit is 8 hours (480 minutes), but this along with some other settings can be changed in the `config/parentalcontrols.json` file. This can be reloaded on the fly with the `/parental reload` command. As a player, you can query your time remaining with the `/parental remaining` command. The time remaining is internally counted per-tick, assuming that the server runs at 20 ticks per second.