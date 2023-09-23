# fs2-live-server

```bash
# build 
scala-cli --power package LiveServer.scala -f -o ls
# run live server
./ls --entry-file=index.html --watch ./index.html --proxy api:http://localhost:8090 --verbose
# run mock server 
pip3 install uvicorn fastapi
uvicorn mock-server:app
```
