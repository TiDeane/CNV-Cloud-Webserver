#!/bin/bash

# Syntax:  ./testgameoflife-simple.sh <ip> <port> <map-filename> <iterations>
# Example: ./testgameoflife-simple.sh 127.0.0.1 8001 glider-10-10.json 10
HOST=$1
PORT=$2
mapFilename=$3
iterations=$4

function test_single_requests {

	curl -X GET http://$HOST:$PORT/gameoflife\?mapFilename=$mapFilename\&iterations=$iterations
}

test_single_requests

