# jchat
A chat client and server written in Java. The protocol for this
service is described in [here][rc].

# Dependencies
* JDK >= 7
* Eclipse or GNU-make for building

# Compiling
It it possible to compile this project using Eclipse by simply
importing the folder to the IDE or on can use the provided Makefile
that creates two .jar files: server.jar and client.jar.

Simply run:

	make jar

# Usage
Server expects a port number as a command argument and client expects
a hostname and a port to connect to the server.

On the command line, one can run:

	java -jar server.jar 8000
	java -jar client.jar localhost 8000

[rc]: http://www.dcc.fc.up.pt/~rprior/1516/RC/trabalho/enunciado.html
