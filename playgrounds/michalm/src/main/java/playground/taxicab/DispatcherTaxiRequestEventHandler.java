/* *********************************************************************** *
 * project: michalm
 * DispatcherTaxiRequestEventHandler.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2011 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package playground.taxicab;

import org.matsim.core.events.handler.EventHandler;

/**
 * @author nagel
 *
 */
public interface DispatcherTaxiRequestEventHandler extends EventHandler {
	
	void handleEvent( DispatcherTaxiRequestEvent ev ) ;

}
