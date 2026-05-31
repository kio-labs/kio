Test server: `docker run -it --rm --platform linux/amd64 -v "${PWD}/config:/config" -v "${PWD}/reports:/reports" --name fuzzingclient crossbario/autobahn-testsuite:latest wstest -m fuzzingclient -s /config/fuzzingclient.json`

Test client: `docker run -it --rm -p 9001:9001 -v "${PWD}/config:/config" -v "${PWD}/reports:/reports" --name fuzzingserver crossbario/autobahn-testsuite wstest -m fuzzingserver -s /config/fuzzingserver.json`

