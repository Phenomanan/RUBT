import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.io.*;
import java.util.Random;
import java.util.HashMap;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import GivenTools.*;

/**
 * @author Chris, Manan, Mehul
 
 */

/**
 * This class represents a peer
 *
 * @author Chris
 *
 */
class Peer {
	public int port;
	public String IP;
	public byte[] peerID;

	public Peer(int port, String IP, byte[] peerID) {
		this.port = port;
		this.IP = IP;
		this.peerID = peerID;

	}

}

/**
 *
 * @author Manan This thread downloads pieces from the peer
 */
class Downloader extends Thread {
	private Peer peer;
	private TorrentInfo info;
	byte[] b;
	public BitSet available;

	// Constructor takes in a peer that wants to download
	public Downloader(Peer peer, TorrentInfo info, byte[] b) {
		this.peer = peer;
		this.info = info;
		this.b = b;
        this.available = new BitSet(RUBTClient.pieces.length); // The size of one piece
		this.available.clear();
	}

	public void run() {
		int x = 0;
		boolean retry = false;
		while (RUBTClient.remaining > 0 && x < 500) {// Our main download loop
			System.out.printf("%n------Download Try #%d------%n", x + 1);
			System.out.println("Remaining pieces: " + RUBTClient.remaining);
			try {
				download();
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				System.out.println("Thread download failed."+e1); // FLAG
				//e1.printStackTrace();
			}
			x++;
			if (RUBTClient.pieces[RUBTClient.pieces.length - 1] == 2 && !retry) {
				if((RUBTClient.remaining <= 0))
					break;
				System.out.println("All pieces requested; must wait, then re-request.");
				retry = true;
				System.out.printf("Availibilty: %d pieces%n", this.available.cardinality());
				try {
					TimeUnit.SECONDS.sleep(120);// Wait 1 interval time
				} catch (InterruptedException e) {
				}
			}
		}

		RUBTClient.decrementActiveThreads();//this might not work EDIT-FLAG
		System.out.print("Thread finished. Threads active: "+RUBTClient.numActiveThreads);
		System.out.println(" Pieces left: "+RUBTClient.remaining);
		return;
	}

	/**
	 * @author Manan, Mehul
	 * @throws IOException
	 */
	@SuppressWarnings({ "resource", "unused" })
	public void download() throws IOException {
		Socket peerSocket; // Socket connecting to peer
		String message; // Holds received/sent messages
		int x;
		final ReentrantLock lock = new ReentrantLock(); // This is to lock
														// methods so that they
														// are not accessed by
														// two threads
														// simultaneously

		try {
			peerSocket = new Socket(peer.IP, peer.port); // Set peer socket
		} catch (IOException e) {
			System.out.println("Could not instantiate peer socket.");
			throw e; // Throw the exception because program should not continue
			// if cannot connect
		}

		// System.out.println("Peer socket successfully connected."); // Flag
		DataInputStream peerInput = null;
		DataOutputStream peerOutput = null;
		try {

			peerInput = new DataInputStream(peerSocket.getInputStream()); // Open
			// inputstream
			peerOutput = new DataOutputStream(peerSocket.getOutputStream()); // Open
			// outputstream

		} catch (IOException e) {
			System.err.println("there was a problem opening up the streams");
		}

		// System.out.println("Input/Output streams successfully opened."); //
		// Flag

		byte[] peerLine; // Will be used for handshake and message
		peerLine = RUBTClient.handshake(info.info_hash.array(), b); // Set it to
																	// equal the
		// handshake
		try {
			peerOutput.write(peerLine); // Send handshake to peer
			peerInput.read(peerLine); // Receive handshake
		} catch (IOException e) {
			System.err.println("problem reading or writing to the peer");
		}

		// peerSocket.setSoTimeout(10000);
		String response_SHA = new String(peerLine).substring(28, 48);
		String our_SHA = new String(info.info_hash.array());

		// System.out.println("Our SHA-1: " + our_SHA + " , Peer's SHA-1: " +
		// response_SHA);
		if (!response_SHA.equals(our_SHA)) {
			System.out.println("ERROR: info_hash incorrect.");
			return;
			// throw an exception.
		} else {
			// System.out.println("Handshake successful, SHA-1 Hash
			// confirmed.");
		}

		// Sending 'interested' to peer
		byte[] interested = RUBTClient.message(1, 2).message;
		peerOutput.write(interested);
		int avail = peerInput.available();

		x = 0;

		// System.out.println();
		byte[] temp = RUBTClient.INTERESTED;
		byte[] temp2 = new byte[4];
		peerOutput.write(temp);
		int tries = 1000;
		boolean requesting = false;

		while (peerInput.available() != 0 || tries > 0) {
			try {
				peerLine[0] = peerInput.readByte();
				// System.out.println("Successful read."); // FLAG
			} catch (EOFException e) {
				try {
					peerOutput.write(temp);
				} catch (SocketException s) {
					System.out.println("The connection is closed."); // FLAG
				}
				tries--;
				// System.out.println("Failed to read."); // FLAG
				continue;
			}
			/* System.out.println("Peer Response: " + peerLine[0]); */

			x = 0;
			if (peerLine[0] >= 0) {
				temp2[0] = peerLine[0];
				temp2[1] = peerInput.readByte();
				temp2[2] = peerInput.readByte();
				temp2[3] = peerInput.readByte();
				x = RUBTClient.parseHex(temp2);
			}

			if (x > 0) {
				peerLine = new byte[x];
				// System.out.printf("Found a message Size: %d; Code: ",x);
				tries = 1000;
				for (x = 0; x < peerLine.length; x++) { // here we read in the
														// actual message
					peerLine[x] = peerInput.readByte();
				}
				message = RUBTClient.parseMessage(peerLine);
				// System.out.println(" "+message);
				// temp = INTERESTED;

				if (message == "unchoke") {
					// System.out.printf("%n#####GOT AN UNCHOKE#####%n");
					lock.lock(); // FLAG
					temp = RUBTClient.whatNext();// we use whatNext() to
													// generate the request
													// message
					lock.unlock();
					peerOutput.write(temp);
					requesting = true;
					// need to wait for response
					// I think we also have to wait one interval time between
					// requests
				}

				if (message == "piece") {
					// System.out.printf("%n#####GOT A PIECE#####%n");
					temp = RUBTClient.addPiece(peerLine);
					peerInput.close();
					peerOutput.close();
					peerSocket.close();
					return;
				}

				if (message == "interested") {
					peerOutput.write(RUBTClient.UNCHOKE);
					requesting = true;
				}

				if(message=="bitfield"){
					setBitField(peerLine); // FLAG
				}

				if(message=="have"){//we set the corresponding bit to true in the bitfield
		            temp2[0] = peerLine[1];
		            temp2[1] = peerLine[2];
		            temp2[2] = peerLine[3];
		            temp2[3] = peerLine[4];
		            x = RUBTClient.parseHex(temp2);
					available.set(x);
				}
			}
			tries--;
			if (!requesting)
				peerOutput.write(temp);
			else
				break;
		}

		// peerSocket.setSoTimeout(10000);
		while (requesting && tries > 0) {
			// System.out.print(".");
			try {
				peerLine[0] = peerInput.readByte();
			} catch (EOFException e) {
				// continue;
				peerLine[0] = -1;
			}

			x = 0;
			if (peerLine[0] >= 0) {
				temp2[0] = peerLine[0];
				temp2[1] = peerInput.readByte();
				temp2[2] = peerInput.readByte();
				temp2[3] = peerInput.readByte();
				x = RUBTClient.parseHex(temp2);
			}

			if (x > 0) {
				peerLine = new byte[x];
				// System.out.printf("Found a message Size: %d; Code: ",x);
				tries = 10;
				for (x = 0; x < peerLine.length; x++) { // here we read in the
														// actual message
					peerLine[x] = peerInput.readByte();

				}
				message = RUBTClient.parseMessage(peerLine);

				if (message == "piece") {
					// System.out.printf("%n#####GOT A PIECE#####%n");
					lock.lock(); // FLAG
					temp = RUBTClient.addPiece(peerLine);
					lock.unlock(); // FLAG
					try {// send a HAVE message for the piece we just downloaded
						peerOutput.write(temp);
					} catch (SocketException s) {
						System.out.println("The connection is closed."); // FLAG
						break;
					}
					temp = RUBTClient.KEEP_ALIVE;
					try {// send a KEEP_ALIVE to keep the connection open
						peerOutput.write(temp);
					} catch (SocketException s) {
						System.out.println("The connection is closed.");
						break;
					}
					lock.lock(); // FLAG
					temp = RUBTClient.whatNext();
					lock.unlock(); // FLAG
					try {
						peerOutput.write(temp);
					} catch (SocketException s) {
						System.out.println("The connection is closed."); // FLAG
						break;
					}
				}
				if (message == "choke") {
					break;
				}
			}
			tries--;
		}

		// close streams and sockets.
		peerInput.close();
		peerOutput.close();
		peerSocket.close();

		// System.out.println("Download Failed, will try again");
		/*
		 * try{ TimeUnit.SECONDS.sleep(30); } catch (InterruptedException e){}
		 */
	}

	/**
	 * @author Mehul
	 * This method just takes in a bitfield message,
	 *  and updates the downloader's available list accordingly
	 * We have to keep in mind that the BitSet always increments by 64,
	 *  and that the bitfield from the message could have extra trailing bits.
	 */
	public void setBitField(byte[] input){
		int x, y, z, temp;

		z = 0;
		//the first byte of the array is the message code
		for(x=1; x<input.length; x++)
			for(y=7; y>=0; y--){
				if(z >= RUBTClient.pieces.length)
					return;
				temp = ((input[x]) >> y) & 1;
			//	System.out.println(z+": "+temp);
				this.available.set(z, temp==1);
				z++;
			}
		return;
	}
}

/**
 *
 * @author Manan This thread uploads pieces to the asking peer
 */
class Uploader extends Thread {
	@SuppressWarnings("unused")
	private Peer peer;

	// Constructor takes in a peer that wants to upload
	public Uploader(Peer peer) {
		this.peer = peer;
	}

	public void run() {


		return;
	}
}

/**
 * A convenience class for testing so that we can access the message_type of the
 * messages being exchanged by the peers
 *
 * @author Chris
 *
 */
class Message {
	public byte[] message;
	public String message_type;

	public Message(String message_type, byte[] message) {
		this.message_type = message_type;
		this.message = message;
	}
}

public class RUBTClient {

	// these global variables are for convenience when sending the messages to
	// the peer

	public static ArrayList<Downloader> downloads = new ArrayList<Downloader>();

	static byte[] KEEP_ALIVE = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
	private static byte[] CHOKE = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0 };
	static byte[] UNCHOKE = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 1 };
	static byte[] INTERESTED = new byte[] { (byte) 00, (byte) 00, (byte) 00, (byte) 01, (byte) 2 };
	private static byte[] UNINTERESTED = new byte[] { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 3 };

	@SuppressWarnings("unused")
	private static Socket peerSocket; // Socket that connects to peer

	public static File sfile;
	public static FileOutputStream sfile_stream;
	static int[] pieces;
	// We use this array to see what pieces we have
	private static int last_piece;
	private static int piece_length;
	// We use these to see what size the piece will be
	public static int remaining;
	@SuppressWarnings("rawtypes")
	public static ArrayList pieceList;
	public static int numActiveThreads; // Tracks how many threads are still alive

	/**
	 *
	 * @param args
	 *            - takes in .torrent file and .mov file name
	 * @throws BencodingException
	 * @throws IOException
	 * @author Mehul, Manan
	 */
	public static void main(String[] args) throws BencodingException, IOException {

		if (args.length != 2) {
			System.out.println("RUBTClient requires two arguments: <.torrent file, .mov file name");
		}
		String tfilename = args[0];
		String sfilename = args[1];

		long numbytes;
		byte[] tfilebytes;
		int x;

		TorrentInfo info;

		sfile = new File(sfilename);
		File tfile = new File(tfilename);

		FileInputStream tfile_stream = null;
		sfile_stream = null;
		try {
			tfile_stream = new FileInputStream(tfile);
			sfile_stream = new FileOutputStream(sfile);

		} catch (IOException e) {
			System.err.println("problem setting up file streams");
		}

		numbytes = tfile.length();
		tfilebytes = new byte[(int) numbytes];

		// close file stream
		tfile_stream.read(tfilebytes);
		tfile_stream.close();

		// This is what we actually needed, not the dictionary
		info = new TorrentInfo(tfilebytes);

		piece_length = info.piece_length;
		pieces = new int[(int) Math.ceil(info.file_length / piece_length) + 1]; // FLAG
		remaining = pieces.length;

		System.out.print("File-length: " + info.file_length);
		System.out.println(" Piece Size: " + piece_length + " #Pieces: " + pieces.length);

		pieceList = new ArrayList<byte[]>(pieces.length);

		for (x = 0; x < pieces.length; x++)
			pieces[x] = 0;
		last_piece = info.file_length - ((x - 1) * piece_length); // FLAG
		// last_piece = 16384; FLAG

		byte[] temp = new byte[4];
		System.out.printf("Piece Lengths:%n Normal Piece: %d	Hex: ", piece_length);
		temp = setHex(temp, piece_length);
		System.out.printf("%n Last Piece: %d	Hex: ", last_piece);
		temp = setHex(temp, last_piece);
		System.out.println();
		/* return; */

		// pieces[0] = 2;

		long startTime = System.currentTimeMillis();
		download(info); // Gets peers, then creates downloader and uploader
						// threads

		while (pieceList.size() > 0) {
			sfile_stream.write((byte[]) pieceList.remove(0));
		}

		x = pieces.length - remaining;
		System.out.println("Finished: Downloaded: " + x + " / " + pieces.length);
		System.out.println("Total download time: " + (System.currentTimeMillis() - startTime) / 1000 + " seconds");
		System.out.println();
		return;
	}

	/*
	 * NOTE: byte[] input MUST be a 4-length byte[] this method encodes the int
	 * value as hex and stores the hex value in the byte array
	 * 
	 * @author Mehul
	 */
	private static byte[] setHex(byte[] input, int value) {
		int x;
		int a, b, c, d, e, f, g, h;
		// 16^0, 16^1, 16^2, 16^3, 16^4, 16^5, 16^6, 16^7

		// System.out.println("Flag: setting hex value for: "+value);

		a = (int) Math.pow(16, 0);
		b = (int) Math.pow(16, 1);
		c = (int) Math.pow(16, 2);
		d = (int) Math.pow(16, 3);
		e = (int) Math.pow(16, 4);
		f = (int) Math.pow(16, 5);
		g = (int) Math.pow(16, 6);
		h = (int) Math.pow(16, 7);

		for (x = 0; x < input.length; x++)
			input[x] &= 0x00;

		x = 0;
		if (value >= h) {
			x = (value - value % h) / h;
			value = value % h;
		}
		// System.out.print(x+" ");
		input[0] = setBits(input[0], 'h', x);

		x = 0;
		if (value >= g) {
			x = (value - value % g) / g;
			value = value % g;
		}
		// System.out.print(x+" ");
		input[0] = setBits(input[0], 'l', x);

		x = 0;
		if (value >= f) {
			x = (value - value % f) / f;
			value = value % f;
		}
		// System.out.print(x+" ");
		input[1] = setBits(input[1], 'h', x);

		x = 0;
		if (value >= e) {
			x = (value - value % e) / e;
			value = value % e;
		}
		// System.out.print(x+" ");
		input[1] = setBits(input[1], 'l', x);

		x = 0;
		if (value >= d) {
			x = (value - value % d) / d;
			value = value % d;
		}
		// System.out.print(x+" ");
		input[2] = setBits(input[2], 'h', x);

		x = 0;
		if (value >= c) {
			x = (value - value % c) / c;
			value = value % c;
		}
		// System.out.print(x+" ");
		input[2] = setBits(input[2], 'l', x);

		x = 0;
		if (value >= b) {
			x = (value - value % b) / b;
			value = value % b;
		}
		// System.out.print(x+" ");
		input[3] = setBits(input[3], 'h', x);

		x = 0;
		if (value >= a) {
			x = (value - value % a) / a;
			value = value % a;
		}
		// System.out.print(x+" ");
		input[3] = setBits(input[3], 'l', x);

		// System.out.println();
		return input;
	}

	/*
	 * input = byte[] of length 4 pos is either 'l' for low or 'h' for high
	 * value is between 0 and 15 (limitations of 4-bit binary) the method sets
	 * the 4 bits specified to the given value
	 * 
	 * @author Mehul
	 */
	private static byte setBits(byte input, char pos, int value) {
		int x, a, b, c, d;
		// 8, 4, 2, 1

		a = 0;
		if (value - 8 >= 0) {
			a = 1;
			value = value - 8;
		}
		b = 0;
		if (value - 4 >= 0) {
			b = 1;
			value = value - 4;
		}
		c = 0;
		if (value - 2 >= 0) {
			c = 1;
			value = value - 2;
		}
		d = 0;
		if (value - 1 >= 0) {
			d = 1;
		}

		if (pos == 'h') {// System.out.println("Flag: high bits set");
			for (x = 7; x > 3; x--)
				input &= ~(1 << x);
			if (a == 1)
				input |= (1 << 7);
			if (b == 1)
				input |= (1 << 6);
			if (c == 1)
				input |= (1 << 5);
			if (d == 1)
				input |= (1 << 4);
		}
		if (pos == 'l') {// System.out.println("Flag: low bits set");
			for (x = 3; x >= 0; x--)
				input &= ~(1 << x);
			if (a == 1)
				input |= (1 << 3);
			if (b == 1)
				input |= (1 << 2);
			if (c == 1)
				input |= (1 << 1);
			if (d == 1)
				input |= (1 << 0);
		}
		return input;
	}

	/*
	 * input = byte[] of length 4 the method parses the array to get the int
	 * value it represents
	 * 
	 * @author Mehul
	 */
	static int parseHex(byte[] input) {
		int ret;
		byte temp;
		int a, b, c, d, e, f, g, h;
		// 16^0, 16^1, 16^2, 16^3, 16^4, 16^5, 16^6, 16^7

		a = (int) Math.pow(16, 0);
		b = (int) Math.pow(16, 1);
		c = (int) Math.pow(16, 2);
		d = (int) Math.pow(16, 3);
		e = (int) Math.pow(16, 4);
		f = (int) Math.pow(16, 5);
		g = (int) Math.pow(16, 6);
		h = (int) Math.pow(16, 7);

		ret = a * (input[3] & 0x0F); // this gets the last 4 bits of ret[3]
		temp = (byte) (input[3] >>> 4);
		ret += b * ((temp & 0x08) + (temp & 0x04) + (temp & 0x02) + (temp & 0x01));

		ret += c * (input[2] & 0x0F);
		temp = (byte) (input[2] >>> 4); // this gets the first 4 bits of ret[2]
		ret += d * ((temp & 0x08) + (temp & 0x04) + (temp & 0x02) + (temp & 0x01));

		ret += e * (input[1] & 0x0F);
		temp = (byte) (input[1] >>> 4);
		ret += f * ((temp & 0x08) + (temp & 0x04) + (temp & 0x02) + (temp & 0x01));

		ret += g * (input[0] & 0x0F);
		temp = (byte) (input[0] >>> 4);
		ret += h * ((temp & 0x08) + (temp & 0x04) + (temp & 0x02) + (temp & 0x01));
		return ret;
	}

	/**
	 * Transforms a byte array into URL String
	 *
	 * @param byte[]
	 * @return URL String
	 */
	private static String toURL(byte bytes[]) throws IOException {

		byte ch;
		int i = 0;

		char hexChar[] = "0123456789ABCDEF".toCharArray();
		StringBuffer out = new StringBuffer(bytes.length * 2);

		while (i < bytes.length) {
			out.append('%');
			ch = (byte) (bytes[i] & 0xF0);
			ch = (byte) (ch >>> 4);
			ch = (byte) (ch & 0x0F);

			out.append(hexChar[(int) ch]);

			ch = (byte) (bytes[i] & 0x0F);
			out.append(hexChar[(int) ch]);

			i++;
		}
		return new String(out);

	}

	/**
	 *
	 * @param info
	 * @throws IOException
	 * @throws BencodingException
	 * @author Mehul, Manan, Chris
	 */
	@SuppressWarnings({ "rawtypes", "unused", "deprecation" })
	public static void download(TorrentInfo info) throws IOException, BencodingException {
		URL link, link2;
		HttpURLConnection conn;
		String message;
		char[] peer_ID;
		BufferedReader reader;
		int x;
		Random rand;

		link = info.announce_url;

		// need to add the required keys to the URL
		message = link.getPath();

		// here we add the info_hash part
		message += "?info_hash=";

		// encoded the info hash into hex
		String info_hash = toURL(info.info_hash.array());
		message += info_hash;

		// here we make the peer_id (semi-random) and add it on to the message
		peer_ID = new char[20];
		rand = new Random();
		peer_ID[0] = 'M';
		peer_ID[1] = 'M';
		peer_ID[2] = 'C';
		for (x = 3; x < 20; x++) {
			peer_ID[x] = (char) (65 + rand.nextInt(26));
		}
		message += "&peer_id=";
		byte[] b = peer_ID.toString().getBytes(Charset.forName("UTF-8"));
		String hex_peer_ID = toURL(b);
		message += hex_peer_ID;
		// here we add the port the client is listening on
		// not sure how to check what port we are listening on
		message += "&port=";
		message += "6969";
		// here we add the total ammount uploaded
		message += "&uploaded=0";
		// here we add the total ammount downloaded
		message += "&downloaded=0";
		// here we add left, i.e. the number of bytes the client still has to
		// download
		message += "&left=";
		message += info.file_length;
		// here I replace the backslash character with a double backslash (to
		// escape it)
		message = message.replace("\\", "\\\\");

		link2 = new URL(link, message);

		conn = (HttpURLConnection) link2.openConnection();
		conn.setRequestMethod("GET");
		DataInputStream tracker_input = null;

		try {
			tracker_input = new DataInputStream(conn.getInputStream());
		} catch (IOException e) {
			System.err.println("ERROR: problem opening up input stream to tracker");
		}

		byte[] tracker_response_in_bytes = new byte[conn.getContentLength()];

		tracker_input.read(tracker_response_in_bytes);

		tracker_input.close();

		Object Obj = null;
		Obj = DecodeResponse(tracker_response_in_bytes);

		@SuppressWarnings("unchecked")
		HashMap<ByteBuffer, Object> response = (HashMap<ByteBuffer, Object>) Obj;
		// ToolKit.print(response);
		// System.out.println();

		Object[] keys = null;
		keys = response.keySet().toArray();

		ArrayList peerList = null;
		for (x = 0; x < keys.length; x++) {
			if (response.get(keys[x]) instanceof ArrayList) {

				peerList = (ArrayList) response.get(keys[x]);
				// peers should be an ArrayList of dictionaries, one for each
				// peer
				// System.out.printf("Found a list! @position: %d%n", x);

			}
		}
		Peer peer = null;
		ArrayList<Peer> p_list = new ArrayList<Peer>();
		p_list = extract(peerList);
		// System.out.println("Peer Connection: port:" + peer.port + " address:"
		// + peer.IP +
		// " peer id:" + peer.peerID.toString());
		
		
		//************************************************************************************************
			//downloads is a global array list that keeps a list of all the connections being maintained.
			//below is a loop that takes the array list of all the peers and establishes a connection with them. 
	
		numActiveThreads = 0; // initialize

		Downloader[] threads = new Downloader[p_list.size()]; // Store the threads!
		for(int i = 0; i < p_list.size(); i++) {
			// CREATE THREAD TO DOWNLOAD
			Downloader d = new Downloader(p_list.get(i), info, b);
			numActiveThreads++; // One active right now
			downloads.add(d);
			d.start(); // Run the thread and download the Rick Roll
			threads[i] = d; // Add em to list
			
			if (numActiveThreads <= 0){
				System.out.println("Error: no active threads");
				return;
			}
			if (!d.isAlive()){
				System.out.println("one of tbe downloads dropped");
				break;
			}
			// FLAG loop
			// System.out.print("NumActiveThreads: " + numActiveThreads); //
			// Flag
			// System.out.println("LEFT WHILE LOOP"); // fLAGG
		}

		//We need a while-loop here that runs until all the threads stop
		long checkpoint = System.currentTimeMillis();
        while(numActiveThreads >= 1){
            if(numActiveThreads <= 0) break;
			if(remaining <= 0) break;
			if((System.currentTimeMillis() - checkpoint) >= 30000){
				System.out.printf("****CHECKPOINT  NumActiveThreads: %d ****%n", numActiveThreads);
				checkpoint = System.currentTimeMillis();
			}
           // System.out.println("In While. numActiveThreads = " + numActiveThreads);
        } // FLAG loop
        
        for(int i = 0; i < p_list.size(); i++){
        	if(threads[i].isAlive()){
        		threads[i].stop();
        		numActiveThreads--;
        	}
        }
        
        System.out.println("Out of main loop; NumActiveThreads: " + numActiveThreads); // Flag
        return;

	}//END OF download()

	/*
	 * This method takes the piece sent by the peer and adds it to the file. If
	 * we already have that piece, the method simply returns.
	 * 
	 * @author Mehul
	 */
	@SuppressWarnings({ "unused", "unchecked" })
	public static byte[] addPiece(byte[] new_piece) throws IOException {
		byte[] temp, temp2;
		int x, index, begin;

		// System.out.println(new_piece[0]); // FLAG

		temp = new byte[4];
		temp2 = new byte[9]; // temp2 is the have message for this piece

		temp = setHex(temp, 5);
		temp2[0] = temp[0];
		temp2[1] = temp[1];
		temp2[2] = temp[2];
		temp2[3] = temp[3];
		temp2[4] = (byte) 4;

		// there is the 1 byte message id at new_piece[0]

		// the first 4 byte block corresponds to the hex-based index of the
		// piece
		// so we read in the bytes and parse it into an int
		temp[0] = new_piece[1];
		temp2[5] = new_piece[1];
		temp[1] = new_piece[2];
		temp2[6] = new_piece[2];
		temp[2] = new_piece[3];
		temp2[7] = new_piece[4];
		temp[3] = new_piece[4];
		temp2[8] = new_piece[4];
		index = parseHex(temp);
		// System.out.println("ArrayList size = "+pieceList.size());
		// System.out.println("Index = "+index);

		if (pieces[index] == 1)// It would just be extra I/O
			return temp2;

		// the next 4 bytes correspond to the begin position(in bytes), this is
		// also in hex
		// so we read in the bytes and parse it
		temp[0] = new_piece[5];
		temp[1] = new_piece[6];
		temp[2] = new_piece[7];
		temp[3] = new_piece[8];
		begin = parseHex(temp);

		// the rest of the message is the actual data piece
		// so we read it into a byte[]
		temp = new byte[new_piece.length - 9];
		for (x = 9; x < new_piece.length; x++) {
			temp[x - 9] = new_piece[x];
		}

		// now we add that piece to the arraylist according to its index
		// we also update our pieces array and the number of remaining pieces
		while (pieceList.size() <= index) {
			pieceList.add(temp);
		}
		pieceList.set(index, temp);

		pieces[index] = 1;
		remaining--;
		// System.out.printf("New piece added at index %d. %d pieces
		// remaining%n",index,remaining);

		return temp2;
	}

	/*
	 * This method returns the request message for the next piece to get. When
	 * we create a request message for a piece we set its index equal to 2 to
	 * indicate that we are trying to get that piece, but we don't have it yet
	 * 
	 * @author Mehul
	 */
	public static byte[] whatNext() {
		int x, pos, size, begin;
		byte[] ret;
		byte[] hex_bytes;

		hex_bytes = new byte[4];

		// here we append the length prefix of the message, which is 13
		// it has to be in hex so we have to convert

		ret = new byte[17];
		// System.out.print("Request message = ");

		hex_bytes = setHex(hex_bytes, 13);
		ret[0] = hex_bytes[0];// <length prefix> = 13
		ret[1] = hex_bytes[1];
		ret[2] = hex_bytes[2];
		ret[3] = hex_bytes[3];

		// next comes the message id, which is just 6
		ret[4] = (byte) 6;

		// here we scan through the array that tells us which pieces we have
		// a '0' indicates that we do not have that piece and no thread is
		// requesting it
		// a '2' indicates that we do not have the piece, but a thread sent a
		// request for it
		for (x = 0; x < pieces.length; x++) { // first we try to find a piece
												// that has a '0'
			if (pieces[x] == 0)
				break;
		}
		if (x == pieces.length) {
			x--;
		}
		if (x == pieces.length - 1 && pieces[x] != 0) { // else we try to find a
														// piece that has a '2'
			for (x = 0; x < pieces.length; x++)
				if (pieces[x] == 2)
					break;
		}
		if (x == pieces.length) {
			x--;
		}
		size = piece_length;
		if (x == pieces.length - 1)
			size = last_piece;
		pieces[x] = 2;
		pos = x;

		// System.out.print(", ");
		hex_bytes = setHex(hex_bytes, pos);// index
		ret[5] = hex_bytes[0];
		ret[6] = hex_bytes[1];
		ret[7] = hex_bytes[2];
		ret[8] = hex_bytes[3];

		// begin is the begin position, in bytes, where this piece would start
		// in the file
		// as always the number has to be in hex
		// begin = pos*piece_length;
		begin = 0;
		// System.out.print(", ");
		hex_bytes = setHex(hex_bytes, begin);// begin
		ret[9] = hex_bytes[0];
		ret[10] = hex_bytes[1];
		ret[11] = hex_bytes[2];
		ret[12] = hex_bytes[3];

		// System.out.print(", ");
		hex_bytes = setHex(hex_bytes, size);// piece length
		ret[13] = hex_bytes[0];
		ret[14] = hex_bytes[1];
		ret[15] = hex_bytes[2];
		ret[16] = hex_bytes[3];

		/*
		 * for(x=0; x<17; x++){ System.out.print(ret[x]); }
		 */// System.out.println();

		return ret;

		// all pieces will be 16384 bytes, but the last piece may be smaller
		// now we have our request message:
		// (4-byte length prefix, message id, 4-byte index, 4-byte begin, 4-byte
		// piece length)

	}

	/*
	 * This method parses the byte array sent by the peer to determine what kind
	 * of message has been sent.
	 * 
	 * @author Mehul
	 */
	public static String parseMessage(byte[] input) {
		if (input.length == 1) {
			if (input[0] == 1)
				return "unchoke";
			if (input[0] == 0)
				return "choke";
			if (input[0] == 2)
				return "interested";
			if (input[0] == 3)
				return "uninterested";
		}
		if (input.length == 5)
			return "have";
		if (input.length == 13)
			return "request";
		if (input[0] == 5)
			return "bitfield";
		if (input[0] == 7)
			return "piece";
		return "unknown";
	}

	/**
	 * this method creates the handshake that is initially sent to the peer.
	 * 
	 * @param byte[]
	 *            info hash, byte[] peer_id
	 * @return byte[]
	 * @author Chris
	 * @throws UnsupportedEncodingException
	 */
	// **** this is important: the size of the message for handshake message is
	// as
	// follows:
	// (after 19 bytes the message starts because of the fixed headers) + (1
	// byte
	// for string identifier) +
	// (first 8 bytes set to 0) + (20 byte SHA-1 of the bencoded form)
	// + (peer id string length of 20)
	// total message size = 19+1+8+20+20 = 68.
	public static byte[] handshake(byte[] info_hash, byte[] peer_id) throws UnsupportedEncodingException {
		String protocol = "BitTorrent protocol";
		byte[] b = protocol.getBytes("UTF-8");
		byte[] message = new byte[68];

		// length of "bittorrent protocol" is 19 bytes
		message[0] = (byte) 19;

		// fill the message with the next 19 bytes AKA the protocol message
		for (int i = 1, j = 0; j < b.length && i <= 20; i++, j++) {
			message[i] = b[j];
		}
		// set next 8 bytes equal to 0
		for (int i = 20; i < 28; i++) {
			message[i] = 0;
		}
		// fill the next 20 bytes in message up with all 20 bytes of the
		// info_hash
		for (int i = 28, j = 0; i <= 47 && j < info_hash.length; i++, j++) {
			message[i] = info_hash[j];
		}
		// fill the last 20 bytes in message up with all 20 bytes of peer_id.
		for (int i = 48, j = 0; i < message.length && j < peer_id.length; i++, j++) {
			message[i] = peer_id[j];
		}
		// *** now the handshake is ready to be sent.

		return message;
	}

	/**
	 * creates a message object that we'll share with the peer. Each if
	 * statement has a loop that converts the length_prefix into a byte array
	 * that represents the length then I fill the return message byte[] with
	 * those bytes.
	 * 
	 * @param int
	 *            length_prefix, int messageID
	 * @author Chris
	 * @return Message
	 */
	// this method is unfinished, for the last three cases we need to get the
	// payload for the message.
	public static Message message(int length_prefix, int message_id) throws UnsupportedEncodingException {
		// messages for the most part are a fixed number of bytes, unless the
		// length_prefix can vary.
		byte[] mess = new byte[length_prefix + 4];

		String message_type = null;
		Message peer_message = new Message(message_type, mess);
		if (length_prefix == 0 && message_id == 0) {
			// keep alive messages have 0 bytes specified with no message ID or
			// payload.
			message_type = "keep-alive";
			mess = KEEP_ALIVE;
			peer_message.message_type = message_type;
			peer_message.message = mess;
		}
		if (length_prefix == 1 && message_id == 0) {
			message_type = "choke";
			mess = CHOKE;
			peer_message.message_type = message_type;
			peer_message.message = mess;
		}
		if (length_prefix == 1 && message_id == 1) {
			message_type = "unchoke";
			mess = UNCHOKE;
			peer_message.message_type = message_type;
			peer_message.message = mess;
		}
		if (length_prefix == 1 && message_id == 2) {
			message_type = "interested";
			mess = INTERESTED;
			peer_message.message_type = message_type;
			peer_message.message = mess;
		}
		if (length_prefix == 1 && message_id == 3) {
			message_type = "uninterested";
			mess = UNINTERESTED;
			peer_message.message_type = message_type;
			peer_message.message = mess;
		}
		// need to get payload

		// we'll ultimately be sending peer_message.message to the peer which is
		// the byte[].
		return peer_message;
	}

	/**
	 * This method takes in the abstract list of dictionaries taken from the
	 * tracker response and locates correct peer ID that we're looking for (in
	 * this case RU1103) then it creates a peer object <int port,String IP> and
	 * returns the peer object.
	 * 
	 * @param abstract
	 *            list
	 * @return Peer object
	 * @author chris
	 * @throws UnsupportedEncodingException
	 */
	@SuppressWarnings({ "unchecked", "unused" })
	public static ArrayList<Peer> extract(@SuppressWarnings("rawtypes") ArrayList list)
			throws UnsupportedEncodingException {
		// use the byte buffers to search through a HashMap<ByteBuffer,Object>
		// for the right information.
		ByteBuffer RUpeer_id = ByteBuffer.wrap(new byte[] { 'p', 'e', 'e', 'r', ' ', 'i', 'd' });
		ByteBuffer RUport = ByteBuffer.wrap(new byte[] { 'p', 'o', 'r', 't' });
		ByteBuffer IP = ByteBuffer.wrap(new byte[] { 'i', 'p' });

		int dictionary_size = list.size();

		String ID = null;
		int port = 0;
		String ip_address = null;
		byte[] peer_id = null;
		ArrayList<Peer> peerList = new ArrayList<Peer>();

		for (int i = 0; i < dictionary_size; i++) {
			HashMap<ByteBuffer, Object> dictionary_map = (HashMap<ByteBuffer, Object>) list.get(i);
			ByteBuffer ru_peer = (ByteBuffer) dictionary_map.get(RUpeer_id);
			port = (Integer) dictionary_map.get(RUport);
			ByteBuffer ru_ip = (ByteBuffer) dictionary_map.get(IP);
			peer_id = ru_peer.array();
			ip_address = new String(ru_ip.array(), "UTF-8");
			ID = new String(ru_peer.array(), "UTF-8");

			Peer peer = new Peer(port, ip_address, peer_id);
			peerList.add(peer);

		}

		return peerList;
	}

	/**
	 * converts a byte[] into an integer
	 * 
	 * @author Chris
	 * @param byte[]
	 * @return int
	 */
	public static int byteArrayToInt(byte[] b) {
		final ByteBuffer bb = ByteBuffer.wrap(b);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		return bb.getInt();
	}

	/**
	 * This method uses the Bencoder2.decode() method to turn a byte[] into an
	 * object that has the decoded information for extracting the peer
	 * information.
	 * 
	 * @author Chris
	 * @param byte[]
	 * @return Object
	 * @throws BencodingException
	 */

	public static Object DecodeResponse(byte[] tracker_response) throws BencodingException {
		Object obj = null;
		try {
			obj = Bencoder2.decode(tracker_response);
			// System.out.println(obj.toString());
		} catch (BencodingException e) {
			System.err.println("problem with decoding");
		}
		return obj;
	}

	/**
	 * @author Manan Decrements the active thread global variable
	 */
	public static void decrementActiveThreads() {
		numActiveThreads--;
		// System.out.println("Number of Active Threads: " + numActiveThreads);
		// // FLAG
	}

}
