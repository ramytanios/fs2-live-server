# fs2-live-server

Scala rewrite of [Live Server](https://github.com/tapio/live-server) 

Build the app:
```bash
scala-cli --power package LiveServer.scala -f -o ls
```
Run the server:
```bash
./ls --entry-file=index.html --watch ./index.html --proxy api:http://localhost:8090 --verbose
```

Run mock server (`pip install uvicorn fastapi`)
```bash
uvicorn mock-server:app
```


