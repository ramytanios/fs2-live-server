# fs2-live-server

Purely functional static server with hot-reload based on the following beautiful libraries 🔥:
- [fs2](https://fs2.io/) 👈🏼
- [http4s](https://http4s.org/) 👈

and built with [Scala CLI](https://scala-cli.virtuslab.org/) 😎

Inspired by NodeJS [Live Server](https://github.com/tapio/live-server) 💡

Flake setup based on the template [Template](https://github.com/buntec/flake-templates) ⚡⚡

For development using [nix](https://nixos.org/download.html) 🔧, execute in the root of the project
```
nix develop
```

Compile using [Scala Native](https://scala-native.org/en/latest/) ⚙️
```bash
./package-native
```

Run the server, for example 🚀🚀
```bash
./live-server --entry-file=index.html --proxy=api:http://localhost:8090
```
