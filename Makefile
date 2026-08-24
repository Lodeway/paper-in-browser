# Everything needed to go from a clean checkout to the running experiment.
#
#   make jar     build the browser-ready Paper jars (clones Paper, applies patches, downgrades to Java 8)
#   make world   pre-generate the world the page ships (needs `make jar` first)
#   make web     build the frontend into web/dist
#   make server  build the Go server
#   make run     serve the whole thing on http://localhost:8090
#   make verify  boot the downgraded server on a real JDK 8 as a sanity check

.PHONY: jar world web server run verify

jar:
	scripts/build-jar.sh

world:
	scripts/make-world.sh

web:
	cd web && bun install && bun run build

server:
	cd server && go build -o paper-labs-server .

run: web server
	server/paper-labs-server -static web/dist

verify:
	scripts/build-jar.sh verify
