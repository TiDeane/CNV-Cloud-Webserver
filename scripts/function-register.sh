#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
source $SCRIPT_DIR/config.sh

aws iam create-role \
	--role-name lambda-role \
	--assume-role-policy-document '{"Version": "2012-10-17","Statement": [{ "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}'

sleep 5

aws iam attach-role-policy \
	--role-name lambda-role \
	--policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

sleep 5

aws lambda create-function \
	--function-name capturetheflag-lambda \
	--zip-file fileb://../capturetheflag/target/capturetheflag-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
	--handler pt.ulisboa.tecnico.cnv.capturetheflag.CaptureTheFlagHandler \
	--runtime java11 \
	--timeout 5 \
	--memory-size 256 \
	--role arn:aws:iam::$AWS_ACCOUNT_ID:role/lambda-role

sleep 5

aws lambda create-function \
	--function-name fifteenpuzzle-lambda \
	--zip-file fileb://../fifteenpuzzle/target/fifteenpuzzle-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
	--handler pt.ulisboa.tecnico.cnv.fifteenpuzzle.FifteenPuzzleHandler \
	--runtime java11 \
	--timeout 5 \
	--memory-size 256 \
	--role arn:aws:iam::$AWS_ACCOUNT_ID:role/lambda-role

sleep 5

aws lambda create-function \
	--function-name gameoflife-lambda \
	--zip-file fileb://../gameoflife/target/gameoflife-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
	--handler pt.ulisboa.tecnico.cnv.gameoflife.GameOfLifeHandler \
	--runtime java11 \
	--timeout 5 \
	--memory-size 256 \
	--role arn:aws:iam::$AWS_ACCOUNT_ID:role/lambda-role

