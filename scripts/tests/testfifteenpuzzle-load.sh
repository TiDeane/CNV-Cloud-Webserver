#!/bin/bash

# Syntax:  ./testfifteenpuzzle-load.sh <ip> <port> <size> <num-of-shuffles>
# Example: ./testfifteenpuzzle-load.sh 127.0.0.1 8000 4 20
HOST=$1
PORT=$2
size=$3
shuffles=$4

function test_batch_requests {
	REQUESTS=3
	CONNECTIONS=1
	ab -n $REQUESTS -c $CONNECTIONS http://$HOST:$PORT/fifteenpuzzle\?size=$size\&shuffles=$shuffles
}

function test_single_requests {

	curl -X GET http://$HOST:$PORT/fifteenpuzzle\?size=$size\&shuffles=$shuffles
}

test_single_requests
test_batch_requests
