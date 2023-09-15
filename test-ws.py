from websocket import create_connection

ws = create_connection("ws://127.0.0.1:8080/ws")
while True:
    result = ws.recv()
    print(result)
