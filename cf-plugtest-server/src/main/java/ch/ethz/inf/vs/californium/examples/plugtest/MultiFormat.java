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
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/
package ch.ethz.inf.vs.californium.examples.plugtest;

import ch.inf.vs.californium.coap.CoAP.ResponseCode;
import ch.inf.vs.californium.coap.MediaTypeRegistry;
import ch.inf.vs.californium.coap.Request;
import ch.inf.vs.californium.coap.Response;
import ch.inf.vs.californium.network.Exchange;
import ch.inf.vs.californium.resources.ResourceBase;

/**
 * This resource implements a test of specification for the ETSI IoT CoAP Plugtests, Paris, France, 24 - 25 March 2012.
 * 
 * @author Matthias Kovatsch
 */
public class MultiFormat extends ResourceBase {

	public MultiFormat() {
		super("multi-format");
		getAttributes().setTitle("Resource that exists in different content formats (text/plain utf8 and application/xml)");
		getAttributes().addContentType(0);
		getAttributes().addContentType(41);
	}

	@Override
	public void processGET(Exchange exchange) {
		Request request = exchange.getRequest();
		Response response = new Response(ResponseCode.CONTENT); // 2.05 content

		String format = "";
		switch (request.getOptions().getAccept()) {
		case MediaTypeRegistry.UNDEFINED:
		case MediaTypeRegistry.TEXT_PLAIN:
			response.getOptions().setContentFormat(MediaTypeRegistry.TEXT_PLAIN);
			format = "Status type: \"%s\"\nCode: \"%s\"\nMID: \"%s\"\nAccept: \"%s\"";

			break;

		case MediaTypeRegistry.APPLICATION_XML:
			response.getOptions().setContentFormat(MediaTypeRegistry.APPLICATION_XML);
			format = "<status type=\"%s\" code=\"%s\" mid=\"%s\" accept=\"%s\"/>";

			break;

		default:
			response = new Response(ResponseCode.NOT_ACCEPTABLE);
			format = "text/plain or application/xml only";
			break;
		}
		
		response.setPayload( 
				String.format(format, 
						request.getType(), 
						request.getCode(), 
						request.getMID(),
						MediaTypeRegistry.toString(request.getOptions().getAccept())) 
				);

		exchange.respond(response);
	}

}