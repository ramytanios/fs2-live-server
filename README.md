# fs2-live-server

Scala rewrite of [Live Server](https://github.com/tapio/live-server) 

Build the app:
```bash
scala-cli --power package project.scala LiveServer.scala -f -o ls
```
Run the server:
```bash
./ls --entry-file=mock-index.html --proxy api:http://localhost:8090 --verbose
```

Equivalent `live-server` command:
```bash
live-server --entry-file=mock-index.html --proxy=/api:http://localhost:8090/api --verbose
```

Build and run the mock server:
```bash
scala-cli --power package project.scala MockServer.scala -f -o ms
./ms
```

TODO: 
1. favicon.ico seems not to work properly (problem with routes)
2. open browser automatically on server startup
3. Better logging 
