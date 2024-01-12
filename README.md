# fs2-live-server

Purely functional static server with hot-reload based on the following beautiful libraries 🔥:
- [fs2](https://fs2.io/) 👈
- [http4s](https://http4s.org/) 👈

and built with [Scala CLI](https://scala-cli.virtuslab.org/) 😎

Inspired by NodeJS [Live Server](https://github.com/tapio/live-server) 💡

Flake setup based on the template [Flake template](https://github.com/buntec/flake-templates) ⚡⚡

If you have [nix](https://nixos.org/download.html) installed and [flakes enabled](https://nixos.wiki/wiki/Flakes#Enable_flakes):

```shell
# JVM app
nix run github:ramytanios/fs2-live-server#jvm --refresh
```

If you want the actual binary, simply replace `run` by `build`.⚙️

This flake also contains a dev shell suitable for working on the app:
```shell
nix develop
```
