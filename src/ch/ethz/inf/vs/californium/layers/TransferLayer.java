/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium CoAP framework.
 ******************************************************************************/
package ch.ethz.inf.vs.californium.layers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import ch.ethz.inf.vs.californium.coap.BlockOption;
import ch.ethz.inf.vs.californium.coap.CodeRegistry;
import ch.ethz.inf.vs.californium.coap.Message;
import ch.ethz.inf.vs.californium.coap.Option;
import ch.ethz.inf.vs.californium.coap.OptionNumberRegistry;
import ch.ethz.inf.vs.californium.coap.Request;
import ch.ethz.inf.vs.californium.coap.Response;
import ch.ethz.inf.vs.californium.util.Properties;

/**
 * The class TransferLayer provides support for
 * <a href="http://tools.ietf.org/html/draft-ietf-core-block">blockwise transfers</a>.
 * 
 * @author Matthias Kovatsch
 */
public class TransferLayer extends UpperLayer {

// Members /////////////////////////////////////////////////////////////////////
	
	private Map<String, Message> incomplete = new HashMap<String, Message>();
	private Map<String, BlockOption> awaiting = new HashMap<String, BlockOption>();
	
	// default block size used for the transfer
	private int defaultSZX;
	
	// Constructors ////////////////////////////////////////////////////////////
	
	/**
	 * Constructor for a new TransferLayer
	 * 
	 * @param defaultBlockSize the block size to use if not indicated by block option
	 */
	public TransferLayer(int defaultBlockSize) {
		
		if (defaultBlockSize==0) {
			defaultBlockSize = Properties.std.getInt("DEFAULT_BLOCK_SIZE");
		}
		
		if (defaultBlockSize > 0) {
		
			defaultSZX = BlockOption.encodeSZX(defaultBlockSize);
			if (!BlockOption.validSZX(defaultSZX)) {
				
				defaultSZX = defaultBlockSize > 1024 ? 6 : BlockOption.encodeSZX(defaultBlockSize & 0x07f0);
				LOG.warning(String.format("Unsupported block size %d, using %d instead", defaultBlockSize, BlockOption.decodeSZX(defaultSZX)));
			}
			
		} else {
			// disable outgoing blockwise transfers
			defaultSZX = -1;
		}
	}
	
	public TransferLayer() {
		this(0);
	}

	// I/O implementation //////////////////////////////////////////////////////
	

	//TODO ETag matching
	
	@Override
	protected void doSendMessage(Message msg) throws IOException {
		
		int sendSZX = defaultSZX;
		int sendNUM = 0;
		
		// block size negotiation
		if (msg instanceof Response && ((Response)msg).getRequest()!=null) {
			BlockOption buddyBlock = (BlockOption) ((Response)msg).getRequest().getFirstOption(OptionNumberRegistry.BLOCK2);
			if (buddyBlock!=null) {
				if (buddyBlock.getSZX()<defaultSZX) {
					sendSZX = buddyBlock.getSZX();
				}
				sendNUM = buddyBlock.getNUM();
			}
		}
		
		// check if message needs to be split up
		if (BlockOption.validSZX(sendSZX) && msg.payloadSize() > BlockOption.decodeSZX(sendSZX)) {
			// split message up using block1 for requests and block2 for responses
			
			Message block = getBlock(msg, sendNUM, sendSZX);
			
			if (block!=null) {
				
				// store if not complete
				if (((BlockOption)block.getFirstOption(OptionNumberRegistry.BLOCK2)).getM()) {
					incomplete.put(msg.exchangeKey(), msg); //TODO timeout to clean up incomplete Map after a while
					LOG.info(String.format("Blockwise transfer cached: %s", msg.exchangeKey()));
				} else {
					LOG.info(String.format("Transfer complete: %s", msg.exchangeKey()));
				}
				
				// send block and wait for reply
				sendMessageOverLowerLayer(block);
				
			} else {
				handleOutOfScopeError(msg);
			}
			
		} else {
			// send complete message
			sendMessageOverLowerLayer(msg);
		}
	}
	
	@Override
	protected void doReceiveMessage(Message msg) {
		
		// TODO combined Block1 and Block2
		
		BlockOption block1 = (BlockOption) msg.getFirstOption(OptionNumberRegistry.BLOCK1);
		BlockOption block2 = (BlockOption) msg.getFirstOption(OptionNumberRegistry.BLOCK2);
		
		Message first = incomplete.get(msg.exchangeKey());
		
		if (block1 == null && block2 == null && !msg.requiresBlockwise()) {
			if (first instanceof Request && msg instanceof Response) {
				if (((Response)msg).getRequest()==null) {
					LOG.severe(String.format("Received unmatched response: %s", msg.key()));
				}
			}
			
			deliverMessage(msg);
		
		} else if (msg instanceof Request) {
			
			if (block1 != null || msg.requiresBlockwise()) {
				
				// handle incoming payload using block1
				
				if (msg.requiresBlockwise()) {
					LOG.info(String.format("Requesting blockwise transfer: %s", msg.exchangeKey()));
					
					if (first!=null) {
						incomplete.remove(msg.exchangeKey());
						LOG.info(String.format("Resetting incomplete transfer: %s", msg.exchangeKey()));
					}
					
					block1 = new BlockOption(OptionNumberRegistry.BLOCK1, 0, BlockOption.encodeSZX(Properties.std.getInt("DEFAULT_BLOCK_SIZE")), true);
				}
				
				LOG.info(String.format("Incoming payload, block1"));
				
				handleIncomingPayload(msg, block1);
					
			} else if (block2 != null) {
				
				// send blockwise response
				
				LOG.info(String.format("Block request received : %s | %s", msg.exchangeKey(), block2.getDisplayValue()));
	
				if (first == null) {
					
					// get current representation
					deliverMessage(msg);
					
				} else {
					
					// use cached representation
					Message resp = getBlock(first, block2.getNUM(), block2.getSZX());
					
					if (resp!=null) {
	
						// update message ID
						resp.setMID(msg.getMID());
						
						BlockOption respBlock = (BlockOption)resp.getFirstOption(OptionNumberRegistry.BLOCK2);
						
						try {
							LOG.info(String.format("Block request responded: %s | %s", resp.exchangeKey(), respBlock.getDisplayValue()));
							
							sendMessageOverLowerLayer(resp);
							
						} catch (IOException e) {
							LOG.severe(String.format("Failed to send block response: %s", e.getMessage()));
						}
						
						// remove transfer context if completed
						if (!respBlock.getM()) {
							incomplete.remove(msg.exchangeKey());
							LOG.info(String.format("Blockwise transfer complete: %s", resp.exchangeKey()));
						}
					} else {
						handleOutOfScopeError(msg.newReply(true));
					}
				}
			}
			
		} else if (msg instanceof Response) {
			
			if (block1 != null) {
			
				// handle blockwise acknowledgement
				
				if (first != null) {
					
					if (!msg.isReset()) {
					
						// send next block
						Message block = getBlock(first, block1.getNUM() + 1, block1.getSZX());
						try {
							sendMessageOverLowerLayer(block);
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						
						return;
						
					} else {
						// cancel transfer
						
						LOG.info(String.format("Block-wise transfer cancelled by peer (RST): %s", msg.exchangeKey()));
						//partialOut.remove(msg.transferID());
						incomplete.remove(msg.exchangeKey());
						
						deliverMessage(msg);
					}
				} else {
					LOG.warning(String.format("Unexpected reply in blockwise transfer dropped: %s", msg.key()));
					//return;
				}
				
			} else if (block2 != null) {
			
				// handle incoming payload using block2
				handleIncomingPayload(msg, block2);
			}
		}
	}
	
	private void handleIncomingPayload(Message msg, BlockOption blockOpt) {
		
		Message initial = incomplete.get(msg.exchangeKey());
		
		if (initial != null) {
			
			BlockOption awaited = awaiting.remove(msg.exchangeKey());
			
			// compare block offsets
			if (blockOpt.getNUM()*blockOpt.getSize()==awaited.getNUM()*awaited.getSize() ) {
								
				// append received payload to first response and update message ID
				initial.appendPayload(msg.getPayload());
				
				// update info
				initial.setMID(msg.getMID());
				initial.setOption(blockOpt);
				
				LOG.info(String.format("Block received: %s | %s", msg.exchangeKey(), blockOpt.getDisplayValue()));
				
			} else {
				LOG.warning(String.format("Wrong block received: %s | %s", msg.exchangeKey(), blockOpt.getDisplayValue()));
			}
			
		} else if (blockOpt.getNUM()==0 && msg.payloadSize()>0) {
			
			// calculate next block num from received payload length
			int size = BlockOption.decodeSZX(blockOpt.getSZX());
			int num = (msg.payloadSize() / size) - 1;
			blockOpt.setNUM(num);
			msg.setOption(blockOpt);
			
			// crop payload
			byte[] newPayload = new byte[(num+1)*size];
	        System.arraycopy(msg.getPayload(), 0, newPayload, 0, newPayload.length);
			msg.setPayload(newPayload);
			
			// create new transfer context
			initial = msg;
			incomplete.put(msg.exchangeKey(), initial);
			
			LOG.info(String.format("Transfer initiated for %s", msg.exchangeKey()));
		} else {
			
			LOG.warning(String.format("Transfer started out of order: %s", msg.key()));
			handleIncompleteError(msg.newReply(true));
			return;
		}
		
		if (blockOpt.getM()) {
			Message reply = null;
			
			int sendSZX = blockOpt.getSZX();
			int sendNUM = blockOpt.getNUM();

			// block size negotiation
			if (sendSZX>defaultSZX) {
				sendNUM = sendSZX/defaultSZX * sendNUM;
				sendSZX = defaultSZX; 
			}
			
			BlockOption awaited = new BlockOption(blockOpt.getOptionNumber(), sendNUM+1, sendSZX, false);
			
			awaiting.put(msg.exchangeKey(), awaited);

			if (msg instanceof Response) {

				reply = new Request(CodeRegistry.METHOD_GET, !msg.isNonConfirmable());
				reply.setPeerAddress(msg.getPeerAddress());

			} else if (msg instanceof Request) {

				// TODO set status code

				reply = msg.newReply(true);
				// picked arbitrarily, cannot decide if created or changed without putting resource logic here
				reply.setCode(CodeRegistry.RESP_CREATED);
				
			} else {
				LOG.severe(String.format("Unsupported message type: %s", msg.key()));
				return;
			}

			// echo options
			reply.setOption(msg.getFirstOption(OptionNumberRegistry.TOKEN));
			reply.setOption(awaited);

			try {
				
				BlockOption replyBlock = (BlockOption)reply.getFirstOption(blockOpt.getOptionNumber());
				LOG.info(String.format("Block replied: %s, %s", reply.key(), replyBlock.getDisplayValue()));
				
				sendMessageOverLowerLayer(reply);
	

			} catch (IOException e) {
				LOG.severe(String.format("Failed to request block: %s", e.getMessage()));
			}
		} else {
			deliverMessage(initial);
			incomplete.remove(msg.exchangeKey());
			awaiting.remove(msg.exchangeKey());
		}
	}
	
	private void handleOutOfScopeError(Message resp) {
		
		resp.setCode(CodeRegistry.RESP_BAD_REQUEST);
		resp.setPayload("BlockOutOfScope");
		
		try {
			sendMessageOverLowerLayer(resp);
			LOG.info(String.format("Out-of-scope block request rejected | %s", resp.key()));
			
		} catch (IOException e) {
			LOG.severe(String.format("Failed to send error message: %s", e.getMessage()));
		}
	}
	
	private void handleIncompleteError(Message resp) {
		
		resp.setCode(CodeRegistry.RESP_REQUEST_ENTITY_INCOMPLETE);
		resp.setPayload("Start with block num 0");
		
		try {
			sendMessageOverLowerLayer(resp);
			LOG.info(String.format("Incomplete request rejected | %s", resp.key()));
			
		} catch (IOException e) {
			LOG.severe(String.format("Failed to send error message: %s", e.getMessage()));
		}
	}
	
	
	// Static Methods //////////////////////////////////////////////////////////

	private static Message getBlock(Message msg, int num, int szx) {
		
		int blockSize = 1 << (szx + 4);
		int payloadOffset = num * blockSize;
		int payloadLeft = msg.payloadSize() - payloadOffset;
		
		if (payloadLeft > 0) {
			Message block = new Message(msg.getType(), msg.getCode());
			
			block.setMID(msg.getMID());
			block.setPeerAddress(msg.getPeerAddress());
			
			// use same options
			for (Option opt : msg.getOptionList()) {
				block.addOption(opt);
			}
			
			// calculate 'more' bit 
			boolean m = blockSize < payloadLeft;
			
			// limit block size to size of payload left
			if (!m) {
				blockSize = payloadLeft;
			}
			
			// copy payload block
			
			byte[] blockPayload = new byte[blockSize];
			System.arraycopy(msg.getPayload(), payloadOffset, 
				blockPayload, 0, blockSize);
			
			block.setPayload(blockPayload);
			
			Option blockOpt = null;
			if (msg instanceof Request) {
				blockOpt = new BlockOption(OptionNumberRegistry.BLOCK1, num, szx, m);
			} else {
				blockOpt = new BlockOption(OptionNumberRegistry.BLOCK2, num, szx, m);
			}
			block.setOption(blockOpt);
			
			return block;
			
		} else {
			return null;
		}
	}
}