from fastapi import FastAPI

app = FastAPI()


@app.get("/api")
async def root():
    return {"message": "Hello World"}

@app.get("/api/alive")
async def alive():
    return {"message": "Im alive"}

@app.get("/api/version")
async def version():
    return {"message": "0.0.1"}
