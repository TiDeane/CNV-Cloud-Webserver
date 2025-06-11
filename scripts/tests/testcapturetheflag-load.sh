#!/bin/bash

# Syntax:  ./testcapturetheflag-load.sh <ip> <port> <grid-size> <num-blue-agents> <num-red-agents> <flag-placement-type>
# Example: ./testcapturetheflag-load.sh 127.0.0.1 8001 20 5 5 A
HOST=$1
PORT=$2
gridSize=$3
numRedAgents=$4
numBlueAgents=$5
flagPlacementType=$6

function test_batch_requests {
	REQUESTS=4
	CONNECTIONS=1
	ab -n $REQUESTS -c $CONNECTIONS http://$HOST:$PORT/capturetheflag\?gridSize=$gridSize\&numBlueAgents=$numBlueAgents\&numRedAgents=$numRedAgents\&flagPlacementType=$flagPlacementType
}

function test_single_requests {

	curl -X GET http://$HOST:$PORT/capturetheflag\?gridSize=$gridSize\&numBlueAgents=$numBlueAgents\&numRedAgents=$numRedAgents\&flagPlacementType=$flagPlacementType
}

test_single_requests
test_batch_requests
