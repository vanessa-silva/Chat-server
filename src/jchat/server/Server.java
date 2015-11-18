package jchat.server;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;

import jchat.message.ChatMessage;
import jchat.server.User.State;

class User {
	enum State {INSIDE, OUTSIDE, INIT};

	SocketChannel sc;
	State state;
	String curCommand;
	Room room;

	User(SocketChannel sc) {
		this.sc = sc;
		state = State.INIT;
		curCommand = "";
	}

    void setState(State state){
    	this.state = state;
    }
    
    void setRoom(Room room) {
    	this.room = room;
    }
	
	void error() {
		try {
			sc.write(ByteBuffer.wrap("ERROR\n".getBytes()));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	void exitRoom() {
		
	}
}

class Room {
	Set<User> usersRoom;
	String name;
	
	Room(String name) {
		this.name = name;
		usersRoom = new TreeSet<User>();
	}
	
	User[] getArrayUser() {
		return usersRoom.toArray(new User[usersRoom.size()]);
	}
	
	void joinUser(User user) {
		usersRoom.add(user);
	}
	
	void leftUser(User user) {
		if(usersRoom.size() != 0)
			usersRoom.remove(user);
	}
	
	String getName() {
		return name;
	}
}

public class Server {
    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

  // Decoder for incoming text -- assume UTF-8
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();
  static private final CharsetEncoder encoder = charset.newEncoder();
  
  static HashMap<SocketChannel, User> userMap = new HashMap<SocketChannel, User>();
  static HashMap<String, User> nameMap = new HashMap<String, User>();
  static private HashMap<String, Room> roomMap = new HashMap<String, Room>();
  
  //Regex for command process
  static private final String nickRegex = "nick .+";
  static private final String joinRegex = "join .+";
  static private final String leaveRegex = "leave.*";
  static private final String byeRegex = "bye.*";
  static private final String privateRegex = "priv .+ .+";

  static public void main( String args[] ) throws Exception {
    // Parse port from command line
    int port = Integer.parseInt(args[0]);
    
    try {
      // Instead of creating a ServerSocket, create a ServerSocketChannel
      ServerSocketChannel ssc = ServerSocketChannel.open();

      // Set it to non-blocking, so we can use select
      ssc.configureBlocking(false);

      // Get the Socket connected to this channel, and bind it to the
      // listening port
      ServerSocket ss = ssc.socket();
      InetSocketAddress isa = new InetSocketAddress(port);
      ss.bind(isa);

      // Create a new Selector for selecting
      Selector selector = Selector.open();

      // Register the ServerSocketChannel, so we can listen for incoming
      // connections
      ssc.register(selector, SelectionKey.OP_ACCEPT);
      System.out.println("Listening on port "+port);

      while (true) {
        // See if we've had any activity -- either an incoming connection,
        // or incoming data on an existing connection
        int num = selector.select();

        // If we don't have any activity, loop around and wait again
        if (num == 0) {
          continue;
        }

        // Get the keys corresponding to the activity that has been
        // detected, and process them one by one
        Set<SelectionKey> keys = selector.selectedKeys();
        Iterator<SelectionKey> it = keys.iterator();
        while (it.hasNext()) {
          // Get a key representing one of bits of I/O activity
          SelectionKey key = it.next();

          // What kind of activity is it?
          if ((key.readyOps() & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT) {

            // It's an incoming connection.  Register this socket with
            // the Selector so we can listen for input on it
            Socket s = ss.accept();
            System.out.println("Got connection from "+s);

            // Make sure to make it non-blocking, so we can use a selector
            // on it.
            SocketChannel sc = s.getChannel();
            sc.configureBlocking(false);

            // Register it with the selector, for reading
            sc.register(selector, SelectionKey.OP_READ);
            
            // Map it to a user
            User user = new User(sc);
            userMap.put(sc, user);

          } else if ((key.readyOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {

        	  SocketChannel sc = null;

        	  try {

        		  // It's incoming data on a connection -- process it        		  
        		  sc = (SocketChannel)key.channel();

        		  boolean ok = processInput(sc);

        		  // If the connection is dead, remove it from the selector
        		  // and close it
        		  if (!ok) {
        			  key.cancel();      
        			  
            		  //Delete user
        			  userMap.remove(sc);
            		  if (userMap.containsKey(sc))
            			  userMap.get(sc).exitRoom();
            		  
        			  Socket s = null;
        			  try {
        				  s = sc.socket();
        				  System.out.println( "Closing connection to "+s );
        				  s.close();
        			  } catch( IOException ie ) {
        				  System.err.println( "Error closing socket "+s+": "+ie );
        			  }
        		  }

        	  } catch( IOException ie ) {

        		  // On exception, remove this channel from the selector
        		  key.cancel();
        		  
        		  //Delete user
        		  if (userMap.containsKey(sc))
        			  userMap.get(sc).exitRoom();
        		  userMap.remove(sc);

        		  try {
        			  sc.close();
        		  } catch( IOException ie2 ) { System.out.println( ie2 ); }

        		  System.out.println( "Closed "+sc );
        	  }
          }
        }

        // We remove the selected keys, because we've dealt with them.
        keys.clear();
      }
    } catch( IOException ie ) {
    	System.err.println( ie );
    }
  }

  // Just read the message from the socket and send it to stdout
  static private boolean processInput(SocketChannel sc) throws IOException {
	User user = userMap.get(sc);
	buffer.clear();
	
    // Read the message to the buffer
	sc.read(buffer);
	
	buffer.flip();
	
	if (buffer.limit()==0) {		
    	return false;
	}
	
	// Decode and print the message to stdout
	String message = decoder.decode(buffer).toString();
	boolean first = true;
	for (String msg : message.split("\n")) {
		if (first)
			user.curCommand = user.curCommand.concat(msg);
		else {
			handleMessage(user, user.curCommand);
			user.curCommand = msg;
		}
		first = false;
	}
	if (message.endsWith("\n")) {
		handleMessage(user, user.curCommand);
		user.curCommand = "";
	}
    return true;
  }
 
  static void handleMessage(User user, String msg) {	  	 
	  if (user.state == User.State.INIT && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && nameMap.containsKey(msg.split(" ")[1])) {
		  user.error();
	  }
	  else if (user.state == User.State.INIT && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && !nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //remove username from nameMap
		  nameMap.put(nick, user);
		  user.setState(User.State.OUTSIDE);
		  //return OK
	  }
	  else if (user.state == User.State.OUTSIDE && msg.startsWith("/")
			  && Pattern.matches(joinRegex, msg.substring(1))) {
		  String roomName = msg.split(" ")[1];
		  //join room
		  user.setState(User.State.INSIDE);
		  //send OK to user
		  //send JOINED nick to other users in room
	  }
	  else if (user.state == User.State.OUTSIDE && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && nameMap.containsKey(msg.split(" ")[1])) {
		  //return ERROR
	  }
	  else if (user.state == User.State.OUTSIDE && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && !nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //remove username from nameMap
		  nameMap.put(nick, user);
		  //return OK
	  }
	  else if (user.state == User.State.INSIDE 
			  && (msg.startsWith("//") || !msg.startsWith("/"))) {
		  String message = msg.substring(1);
		  //send MESSAGE name msg to all users in room
	  }
	  else if (user.state == User.State.INSIDE 
			  && msg.startsWith("/") && Pattern.matches(nickRegex, msg.substring(1))
			  && nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //ERROR
	  }
	  else if (user.state == User.State.INSIDE 
			  && msg.startsWith("/") && Pattern.matches(nickRegex, msg.substring(1))
			  && !nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //remove username from nameMap
		  nameMap.put(nick, user);
		  //return OK
		  //send NEWNICK nome_antigo nome to all
	  }
	  else if (user.state == User.State.INSIDE && msg.startsWith("/")
			  && Pattern.matches(joinRegex, msg.substring(1))) {
		  String room = msg.split(" ")[1];
		  //send JOINED nome to all users in room
		  //send LEFT nome to all users in old room
	  }
	  else if (user.state == User.State.INSIDE && msg.startsWith("/")
			  && Pattern.matches(leaveRegex, msg.substring(1))) {		  
		  user.setState(User.State.OUTSIDE);
		  //send LEFT nome to all users in old room
	  }
	  else if (msg.startsWith("/") && Pattern.matches(byeRegex, msg.substring(1))) {		  
		  //remove user from hashtables?
		  //send LEFT nome to all users in old room
		  //send BYE to user
	  }
	  // caso para: user.state == User.State.INSIDE && user -> close connection ?
	  else {		  
		  user.error();
	  }
	  System.out.println("MESSAGE: " + msg);
  }
  
  static private void sendMessage(SocketChannel sc, ChatMessage message) throws IOException {
	  sc.write(encoder.encode(CharBuffer.wrap(message.toString())));
  }
  
  
}
