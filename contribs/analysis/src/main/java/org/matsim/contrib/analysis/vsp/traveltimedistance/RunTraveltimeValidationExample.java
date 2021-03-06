/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
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

/**
 * 
 */
package org.matsim.contrib.analysis.vsp.traveltimedistance;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

/**
 * @author  jbischoff
 * An example how to validate car travel times in MATSim against an external source. In this example, the Here Maps API is used.
 * 
 */

public class RunTraveltimeValidationExample {

	/**
	 * 
	 * @param args
	 *  Arguments to pass:
  <ol type="1">
  	<li>A MATSim Plans file</li>
  	<li>A MATSim Events file</li>
  	<li>A MATSim Network file</li>
  	<li>EPSG-Code of your coordinate system</li>
  	<li>HERE Maps APP ID, see here.com</li>
  	<li>HERE Maps APP code, see here.com</li>
	<li>Output folder location</li>
	<li>The date to validate travel times for, format: YYYY-MM-DD</li>

  	<li>(Optional: The number of trips to validate)</li>

	</ol>
	 *  
	 */
	public static void main(String[] args) {
		String plans = args[0];
		String events = args[1];
		String network = args [2];
		String epsg = args[3];
		String appId = args[4];
		String appCode = args[5];
		Integer tripsToValidate = null;
		String outputfolder = args[6];
		String date = args[7];
		if (args.length>8){
			tripsToValidate = Integer.parseInt(args[8]);
		}
		
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(scenario.getNetwork()).readFile(network);
		new PopulationReader(scenario).readFile(plans);
		CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(epsg, TransformationFactory.WGS84);
		HereMapsRouteValidator validator = new HereMapsRouteValidator(outputfolder, appId, appCode, date, transformation);
		validator.setWriteDetailedFiles(true);
		TravelTimeValidationRunner runner;
		if (tripsToValidate != null){
		runner = new TravelTimeValidationRunner(scenario.getNetwork(), scenario.getPopulation(), events, outputfolder, validator, tripsToValidate);
		}
		else  {
			runner = new TravelTimeValidationRunner(scenario.getNetwork(), scenario.getPopulation(), events, outputfolder,  validator);
		}
		runner.run();
	}
	
}
