#!/bin/bash

[ "$CIRCLE_NODE_INDEX" == "0" ] && sudo cp -f src/test/resources/my.cnf /etc/my.cnf
[ "$CIRCLE_NODE_INDEX" == "1" ] && sudo cp -f src/test/resources/my-gtid.cnf /etc/my.cnf
