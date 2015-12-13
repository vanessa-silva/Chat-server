CC=javac
NAME=jchat
SERVER=ChatServer
CLIENT=ChatClient
BINDIR=bin
LIBDIR=lib
FLAGS=-d $(BINDIR)
MAIN=server.ChatServer
MAIN2=client.ChatClient
#LIBS="$(LIBDIR)/lanterna-2.1.7.jar:$(LIBDIR)/junit-4.10.jar"
#TESTS=$(wildcard src/tests/*.java)

all: $(SERVER) $(CLIENT)

$(SERVER):
	@mkdir -p bin
	@echo Byte-compiling $(SERVER)
	@$(CC) $(FLAGS) $(wildcard src/$(NAME)/server/*.java) $(wildcard src/$(NAME)/message/*.java)

$(CLIENT):
	@mkdir -p bin
	@echo Byte-compiling $(CLIENT)
	@$(CC) $(FLAGS) $(wildcard src/$(NAME)/client/*.java) $(wildcard src/$(NAME)/message/*.java)


jar: $(SERVER) $(CLIENT)
	@echo Packing jar file
	@cd $(BINDIR);\
	jar cvfe server.jar $(NAME).$(MAIN) ./*;\
	mv server.jar ..
	@cd $(BINDIR);\
	jar cvfe client.jar $(NAME).$(MAIN2) ./*;\
	mv client.jar ..
#@cd $(LIBDIR);\
#	jar xf lanterna-2.1.7.jar;\
#	jar xf junit-4.10.jar
#	@cd $(LIBDIR);\
#	jar uf ../$(NAME).jar com org junit
#	@rm -fr $(LIBDIR)/com $(LIBDIR)/org $(LIBDIR)/junit $(LIBDIR)/META-INF $(LIBDIR)/LICENSE.txt

exe: jar
	launch4j $(NAME).xml

#TODO: Unit-tests on Makefile
test: $(NAME)
	cd bin/; java -ea -cp ".:../lib/junit-4.10.jar" org.junit.runner.JUnitCore med.TestBuffer

javadoc:
	@javadoc -author -version -d html -sourcepath src -private -subpackages med -link http://docs.oracle.com/javase/7/docs/api/ -link http://wiki.lanterna.googlecode.com/git/apidocs/2.1 -classpath "lib/lanterna-2.1.7.jar;lib/junit-4.10.jar"

clean:
	rm -f $(NAME).exe $(NAME).jar
	rm -fr $(BINDIR)/*
	rm -f *~
	rm -f src/$(NAME)/*~
	rm -f src/tests/*~
	rm -fr html/*
