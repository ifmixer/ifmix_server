#!/bin/sh
wrk -t4 -c100 -d30s --latency \
  -s findById.lua \
  http://localhost:3001/customer/core/query/todo/findById
