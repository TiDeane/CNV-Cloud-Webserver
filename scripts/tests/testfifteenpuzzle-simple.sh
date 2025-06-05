#!/bin/bash

# Syntax:  ./testfifteenpuzzle-simple.sh <ip> <port> <size> <num-of-shuffles>
# Example: ./testfifteenpuzzle-simple.sh 127.0.0.1 8001 4 20
HOST=$1
PORT=$2
size=$3
shuffles=$4

function test_single_requests {

	curl -X GET http://$HOST:$PORT/fifteenpuzzle\?size=$size\&shuffles=$shuffles
}

test_single_requests

