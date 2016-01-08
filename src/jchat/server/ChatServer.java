package jchat.server;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;

import jchat.message.ChatMessage;
import jchat.message.ChatMessage.MessageType;
import jchat.server.User.State;

class User implements Comparable<User> {
	enum State {INSIDE, OUTSIDE, INIT};

	SocketChannel sc;
	State state;
	String userName;
	String curCommand;
	Room room;

	User(SocketChannel sc) {
		this.sc = sc;
		state = State.INIT;
		this.userName = "";
		curCommand = "";
	}
	
	@Override
	public int compareTo(User other){
		return this.userName.compareTo(other.userName);
	}
	
	void setName(String userName) {
		this.userName = userName;
	}
	
	String getName() {
		return userName;
	}

    void setState(State state) {
    	this.state = state;
    }
    
    State getState() {
    	return state;
    } 
    
    void setRoom(Room room) {
    	this.room = room;
    }
    
    Room getRoom() {
    	return room;
    }
    
    SocketChannel getSocket(){
    	return sc;
    }
	
	void exitRoom() {
		room.leftUser(userName);
		User[] userList = room.getArrayUser();

		for(User u : userList) {
			try {
				ChatServer.sendLeftMessage(u, userName);
			} catch (IOException ie) {
				System.err.println("Error sending left message: " + ie);
			}
		}

		if (userList.length == 0)
			ChatServer.roomMap.remove(room.getName());
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
	
	void leftUser(String username) {
		User user = ChatServer.nameMap.get(username);
		if(usersRoom.size() != 0)
			usersRoom.remove(user);
	}
	
	String getName() {
		return name;
	}
}

public class ChatServer {
    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

  // Decoder for incoming text -- assume UTF-8
  static private final Charset charset = Charset.forName("UTF8");
  static private final CharsetDecoder decoder = charset.newDecoder();
  static private final CharsetEncoder encoder = charset.newEncoder();
  
  static HashMap<SocketChannel, User> userMap = new HashMap<SocketChannel, User>();
  static HashMap<String, User> nameMap = new HashMap<String, User>();
  static HashMap<String, Room> roomMap = new HashMap<String, Room>();
  
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
            		  if (userMap.containsKey(sc)){
            			  User user = userMap.get(sc);
            			  
            			  if (user.getState() == State.INSIDE)
            				  userMap.get(sc).exitRoom();
            			  
            			  nameMap.remove(user.getName());
            			  userMap.remove(sc);
            		  }
            		  
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
        		  if (userMap.containsKey(sc)){
        			  User user = userMap.get(sc);
        			  
        			  if (user.getState() == State.INSIDE)
        				  userMap.get(sc).exitRoom();
        			  
        			  nameMap.remove(user.getName());
        			  userMap.remove(sc);
        		  }

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
 
  static void handleMessage(User user, String msg) throws IOException {	  	 
	  if (user.state == User.State.INIT && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //ERROR
		  sendErrorMessage(user, "There is already a user with nick "+ nick);
	  }
	  else if (user.state == User.State.INIT && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && !nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  user.setName(nick);
		  nameMap.put(nick, user);		  
		  user.setState(User.State.OUTSIDE);
		  //return OK
		  sendOkMessage(user);
	  }
	  else if (user.state == User.State.OUTSIDE && msg.startsWith("/")
			  && Pattern.matches(joinRegex, msg.substring(1))) {
		  String roomName = msg.split(" ")[1];
		  
		  if(!roomMap.containsKey(roomName))
			  roomMap.put(roomName, new Room(roomName));
		  user.setRoom(roomMap.get(roomName));
		  user.setState(User.State.INSIDE);
		  //send OK to user
		  sendOkMessage(user);
		  roomMap.get(roomName).joinUser(user);
		  User[] userList = roomMap.get(roomName).getArrayUser();
		  
		  for(User u : userList) {
			//send JOINED nick to other users in room
			  if (u != user) {
				  sendJoinedMessage(u, user.getName());
			  }
		  }

	  }
	  else if (user.state == User.State.OUTSIDE && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //return ERROR
		  sendErrorMessage(user, "There is already a user with nick "+ nick);
	  }
	  else if (user.state == User.State.OUTSIDE && msg.startsWith("/")
			  && Pattern.matches(nickRegex, msg.substring(1))
			  && !nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  nameMap.remove(user.userName);
		  user.setName(nick);
		  nameMap.put(nick, user);
		  //return OK
		  sendOkMessage(user);
	  }
	  else if (user.state == User.State.INSIDE 
			  && (msg.startsWith("//") || !msg.startsWith("/"))) {
		  String message;
		  if(!msg.startsWith("/"))
			  message = msg.substring(0);
		  else
			  message = msg.substring(1);
		  
		  Room userRoom = user.getRoom();
	      User[] userList = userRoom.getArrayUser();

	      for (User u : userList) {
	    	  //send MESSAGE name msg to all users in room
	    	  sendMessageMessage(u, user.getName(), message);
	      }
	  }
	  else if (user.state == User.State.INSIDE 
			  && msg.startsWith("/") && Pattern.matches(nickRegex, msg.substring(1))
			  && nameMap.containsKey(msg.split(" ")[1])) {
		  String nick = msg.split(" ")[1];
		  //ERROR
		  sendErrorMessage(user, "There is already a user with nick "+ nick);
	  }
	  else if (user.state == User.State.INSIDE 
			  && msg.startsWith("/") && Pattern.matches(nickRegex, msg.substring(1))
			  && !nameMap.containsKey(msg.split(" ")[1])) {
		  String oldNick = user.getName();
		  String nick = msg.split(" ")[1];
		  nameMap.remove(user.getName());
		  user.setName(nick);
		  nameMap.put(nick, user);
		  //return OK to user
		  sendOkMessage(user);
		  
		  Room userRoom = user.getRoom();
	      User[] userList = userRoom.getArrayUser();

	      for (User u : userList) {
	    	  if (u != user) {
	    		  sendNewnickMessage(u, oldNick, nick);
	          }
	      }
	  }
	  else if (user.state == User.State.INSIDE && msg.startsWith("/")
			  && Pattern.matches(joinRegex, msg.substring(1))) {
		  String roomName = msg.split(" ")[1];
		  //send OK to user
		  sendOkMessage(user);
		  if(!roomMap.containsKey(roomName))
			  roomMap.put(roomName, new Room(roomName));
		  User[] userList = roomMap.get(roomName).getArrayUser();
		  roomMap.get(roomName).joinUser(user);
		  
		  for(User u : userList) {
			  //send JOINED nick to all users in room
			  if (u != user) {
				  sendJoinedMessage(u, user.getName());
			  }
		  }
	
		  user.getRoom().leftUser(user.getName());
		  User[] oldUserList = user.getRoom().getArrayUser();
		  
		  for (User u : oldUserList) {
			  //send LEFT nome to all users in old room
			  sendLeftMessage(u, user.getName());
		  }
		  
		  if(user.getRoom().getArrayUser().length == 0)
			  roomMap.remove(user.getRoom().getName());
		  
		  user.setRoom(roomMap.get(roomName));
	  }
	  else if (user.state == User.State.INSIDE && msg.startsWith("/")
			  && Pattern.matches(leaveRegex, msg.substring(1))) {		  
		  user.setState(User.State.OUTSIDE);
		  //send Ok to user
		  sendOkMessage(user);
		  user.getRoom().leftUser(user.getName());
		  User[] oldUserList = user.getRoom().getArrayUser();
		  
		  for (User u : oldUserList) {
			  //send LEFT name to all users in old room
			  sendLeftMessage(u, user.getName());
		  }
		  
		  if(user.getRoom().getArrayUser().length == 0)
			  roomMap.remove(user.getRoom().getName());

		  user.setRoom(null);
	  }
	  else if (msg.startsWith("/") && Pattern.matches(byeRegex, msg.substring(1))) {		  
		  if (user.state == User.State.INSIDE) 
			  user.exitRoom();
			  
		  user.setRoom(null);
		  
		  //send BYE to user
		  sendByeMessage(user);
		  
		  //remove user from hashtables?
		  //close connection:
		  try {
			  System.out.println("Closing connection to " + user.sc);
			  user.sc.close();
		  } catch (IOException ie) {
			  System.err.println("Error closing socket " + user.sc + ": " + ie);
		  }
		  
		  userMap.remove(user.sc);
		  nameMap.remove(user.getName());
	  }
	  else if (user.state != User.State.INIT && msg.startsWith("/")
			  && Pattern.matches(privateRegex, msg.substring(1))) {
		  String name = msg.split(" ")[1];
		  int length = 7 + name.length();
		  String message = msg.substring(length);
		  
		  if (nameMap.containsKey(name)) {
			  sendOkMessage(user);
			  sendPrivateMessage(nameMap.get(name), user.getName(), message);
		  }
		  else {
			  sendErrorMessage(user, name + ": No such nickname online");
		  }
	  }
	  else {		  
		  sendErrorMessage(user, "Command not supported");
	  }
	  System.out.println("MESSAGE: " + msg);
  }
  
  //To send a message
  static private void sendMessage(SocketChannel sc, ChatMessage message) throws IOException {
	  sc.write(encoder.encode(CharBuffer.wrap(message.toString(false))));
  }
  
  //Send ok message
  static private void sendOkMessage(User receiver) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.OK);
	  sendMessage(receiver.getSocket(), message);
  }
  
  //Send error message
  static private void sendErrorMessage(User receiver, String errorMessage) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.ERROR, errorMessage);
	  sendMessage(receiver.getSocket(), message);
  }
  
  //Send joined message
  static private void sendJoinedMessage(User receiver, String joinNick) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.JOINED, joinNick);
	  sendMessage(receiver.getSocket(), message);
  }
  
  //Send message message
  static private void sendMessageMessage(User receiver, String sender, String messageValue) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.MESSAGE, sender, messageValue);
	  sendMessage(receiver.getSocket(), message);
  }
  
  //Send newnick message
  static private void sendNewnickMessage(User receiver, String oldNick, String newNick) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.NEWNICK, oldNick, newNick);
	  sendMessage(receiver.getSocket(), message);
  }
  
  // Send left message
  static void sendLeftMessage(User receiver, String leftNick) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.LEFT, leftNick);
	  sendMessage(receiver.getSocket(), message);
  }
  
  //Send bye message
  static private void sendByeMessage(User receiver) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.BYE);
	  sendMessage(receiver.getSocket(), message);
  }
  
  //Send private message
  static private void sendPrivateMessage(User receiver, String sender, String messageValue) throws IOException {
	  ChatMessage message = new ChatMessage(MessageType.PRIVATE, sender, messageValue);
	  sendMessage(receiver.getSocket(), message);
  }
}