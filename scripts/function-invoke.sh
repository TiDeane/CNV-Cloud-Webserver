#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source $SCRIPT_DIR/config.sh

aws lambda invoke --function-name gameoflife-lambda out --cli-binary-format raw-in-base64-out --payload '{ "mapFilename": "glider-10-10.json", "iterations": "100" }' --log-type Tail --query 'LogResult' --output text |  base64 -d
cat out
aws lambda invoke --function-name fifteenpuzzle-lambda out --cli-binary-format raw-in-base64-out --payload '{ "shuffles": "5", "size": "5" }' --log-type Tail --query 'LogResult' --output text |  base64 -d
cat out
aws lambda invoke --function-name capturetheflag-lambda out --cli-binary-format raw-in-base64-out --payload '{ "gridSize": "15", "numBlueAgents": "3", "flagPlacementType": "A", "numRedAgents": "3" }' --log-type Tail --query 'LogResult' --output text |  base64 -d
cat out