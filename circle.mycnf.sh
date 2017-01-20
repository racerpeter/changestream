#!/bin/bash

[ "$CIRCLE_NODE_INDEX" == "0" ] && sudo mv src/test/resources/my.cnf /etc/my.cnf
[ "$CIRCLE_NODE_INDEX" == "1" ] && sudo mv src/test/resources/my-gtid.cnf /etc/my.cnf
