package org.matsim.contrib.carsharing.qsim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.carsharing.config.OneWayCarsharingConfigGroup;
import org.matsim.contrib.carsharing.config.TwoWayCarsharingConfigGroup;
import org.matsim.contrib.carsharing.events.NoParkingSpaceEvent;
import org.matsim.contrib.carsharing.events.NoVehicleCarSharingEvent;
import org.matsim.contrib.carsharing.stations.OneWayCarsharingStation;
import org.matsim.contrib.carsharing.stations.TwoWayCarsharingStation;
import org.matsim.contrib.carsharing.vehicles.FFCSVehicle;
import org.matsim.contrib.carsharing.vehicles.StationBasedVehicle;
import org.matsim.core.mobsim.framework.HasPerson;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.core.mobsim.framework.MobsimPassengerAgent;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.agents.BasicPlanAgentImpl;
import org.matsim.core.mobsim.qsim.agents.PlanBasedDriverAgentImpl;
import org.matsim.core.mobsim.qsim.agents.TransitAgentImpl;
import org.matsim.core.mobsim.qsim.agents.WithinDayAgentUtils;
import org.matsim.core.mobsim.qsim.interfaces.MobsimVehicle;
import org.matsim.core.mobsim.qsim.interfaces.Netsim;
import org.matsim.core.mobsim.qsim.pt.PTPassengerAgent;
import org.matsim.core.mobsim.qsim.pt.TransitVehicle;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.NetworkRoutingInclAccessEgressModule;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.Facility;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.withinday.utils.EditRoutes;


/**
 * Current version includes:<ul>
 * <li> two-way carsharing with reservation of the vehicle at the end of the activity preceding the rental.
 * <li> one-way carsharing with each station having a parking capacity with the reservation system as the one with two-way
 * <li> free-floating carsharing with parking at the link of the next activity following free-floating trip, reservation system as the one with two-way cs.
 * <li> end of the free-floating rental is always on the link of the next activity, therefore no egress walk leg
 * </ul>
 * @author balac
 */
public class CarsharingPersonDriverAgentImpl implements MobsimDriverAgent, MobsimPassengerAgent, HasPerson, PlanAgent, PTPassengerAgent {
	/** 
	 * <li> {@link EditRoutes} could probably be used to re-route the car leg. 
	 * <li> It should be possible to extract the agent behavior into something analog to {@link NetworkRoutingInclAccessEgressModule}.
	 * </ul> kai, feb'16
	 */

	private OneWayCarsharingStation endStationOW;

	private ArrayList<StationBasedVehicle> twcsVehicles = new ArrayList<StationBasedVehicle>();
	private ArrayList<StationBasedVehicle> owcsVehicles = new ArrayList<StationBasedVehicle>();
	private ArrayList<FFCSVehicle> ffVehicles = new ArrayList<FFCSVehicle>();	
	
	private Map<Id<Link>, Id<Vehicle>> vehicleIdLocation = new HashMap<Id<Link>, Id<Vehicle>>();

	private CarSharingVehiclesNew carSharingVehicles;

	double beelineFactor = 0.0;

	double walkSpeed = 0.0;

	private final BasicPlanAgentImpl basicAgentDelegate ;
	private final TransitAgentImpl transitAgentDelegate ;
	private final PlanBasedDriverAgentImpl driverAgentDelegate ;

	private LeastCostPathCalculator pathCalculator;



	public CarsharingPersonDriverAgentImpl(final Plan plan, final Netsim simulation, 
			CarSharingVehiclesNew carSharingVehicles, LeastCostPathCalculator pathCalculator) {
		this.pathCalculator = pathCalculator;
		Scenario scenario = simulation.getScenario() ;

		this.basicAgentDelegate = new BasicPlanAgentImpl( plan, scenario, simulation.getEventsManager(), simulation.getSimTimer() ) ;
		this.transitAgentDelegate = new TransitAgentImpl( this.basicAgentDelegate ) ;
		this.driverAgentDelegate = new PlanBasedDriverAgentImpl( this.basicAgentDelegate ) ;

		this.basicAgentDelegate.getModifiablePlan() ; // this lets the agent make a full copy of the plan, which can then be modified

		this.carSharingVehicles = carSharingVehicles;

		beelineFactor = scenario.getConfig().plansCalcRoute().getBeelineDistanceFactors().get("walk");
		walkSpeed = scenario.getConfig().plansCalcRoute().getTeleportedModeSpeeds().get("walk");
		//carsharingVehicleLocations = new ArrayList<ActivityFacility>();

		//if ( scenario.getConfig().plansCalcRoute().isInsertingAccessEgressWalk() ) {
		//	throw new RuntimeException( "does not work with a TripRouter that inserts access/egress walk") ;
		//}
		if ( scenario.getConfig().qsim().getNumberOfThreads() != 1 ) {
			throw new RuntimeException("does not work with multiple qsim threads (will use same instance of router)") ; 
		}
	}

	// -----------------------------------------------------------------------------------------------------------------------------

	@Override
	public final void endActivityAndComputeNextState(final double now) {

		PlanElement pe = this.basicAgentDelegate.getNextPlanElement();
		if (pe instanceof Leg) {
			Leg leg = (Leg)pe;
			
			if (leg.getMode().equals("freefloating")) {
	
				insertFreeFloatingTripWhenEndingActivity(now);			
			}
			else if (leg.getMode().equals("onewaycarsharing")) {
	
				insertOneWayCarsharingTripWhenEndingActivity(now);
			}
	
			else if (leg.getMode().equals("twowaycarsharing")) {
	
				insertRoundTripCarsharingTripWhenEndingActivity(now);
			}
		}

		if (!this.getState().equals(State.ABORT))
			this.basicAgentDelegate.endActivityAndComputeNextState(now);

	}

	private void insertFreeFloatingTripWhenEndingActivity(double now) {
		// (_next_ plan element is (presumably) a leg)
		
		Map<FFCSVehicle, Link> ffvehiclesmap = this.carSharingVehicles.getFfvehiclesMap();
		
		List<PlanElement> planElements = this.basicAgentDelegate.getCurrentPlan().getPlanElements();
		int indexOfInsertion = planElements.indexOf(this.basicAgentDelegate.getCurrentPlanElement()) + 1;

		final List<PlanElement> trip = new ArrayList<PlanElement>();
		
		Scenario scenario = this.basicAgentDelegate.getScenario() ;

		// === walk leg: ===

		NetworkRoute route = (NetworkRoute) ((Leg)this.basicAgentDelegate.getNextPlanElement()).getRoute();
		final Link currentLink = scenario.getNetwork().getLinks().get(route.getStartLinkId());
		final Link destinationLink = scenario.getNetwork().getLinks().get(route.getEndLinkId());

		FFCSVehicle vehicleToBeUsed = findClosestAvailableCar(currentLink);
		if (vehicleToBeUsed == null) {
			this.setStateToAbort(now);
			this.basicAgentDelegate.getEvents().processEvent(new NoVehicleCarSharingEvent(now, currentLink.getId(), "ff"));
			return;
		}
		
		ffVehicles.add(vehicleToBeUsed);
		String ffVehId = vehicleToBeUsed.getVehicleId();
		
		final Link stationLink = ffvehiclesmap.get(vehicleToBeUsed) ;
		Coord coordStation = stationLink.getCoord();
		this.carSharingVehicles.getFfVehicleLocationQuadTree().remove(coordStation.getX(),
				coordStation.getY(), vehicleToBeUsed); 
		
		trip.add( createWalkLeg(currentLink, stationLink, "walk_ff", now) );

		// === car leg: ===		

		trip.add(createCarLeg(stationLink, destinationLink, "freefloating", ffVehId, now));
		
		// === insert trip: ===

		planElements.remove(this.basicAgentDelegate.getNextPlanElement());
		planElements.addAll(indexOfInsertion, trip);

	}

	private void insertOneWayCarsharingTripWhenEndingActivity(double now) {

		List<PlanElement> planElements = this.basicAgentDelegate.getCurrentPlan().getPlanElements();
		int indexOfInsertion = planElements.indexOf(this.basicAgentDelegate.getCurrentPlanElement()) + 1;

		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		final List<PlanElement> trip = new ArrayList<PlanElement>();

		
		String typeOfVehicle = this.getDesiredVehicleType();			

		
		//=== access walk leg: ===
		
		NetworkRoute route = (NetworkRoute) ((Leg)this.basicAgentDelegate.getNextPlanElement()).getRoute();
		final Link currentLink = scenario.getNetwork().getLinks().get(route.getStartLinkId());
		final Link destinationLink = scenario.getNetwork().getLinks().get(route.getEndLinkId());		
		
		OneWayCarsharingStation station = findClosestAvailableOWCar(currentLink, typeOfVehicle);

		if (station == null) {
			this.setStateToAbort(now);
			this.basicAgentDelegate.getEvents().processEvent(new NoVehicleCarSharingEvent(now, route.getStartLinkId(), "ow"));
			return;
		}
		StationBasedVehicle vehicleToBeUsed = station.getVehicles(typeOfVehicle).get(0);
		
		String owVehId = vehicleToBeUsed.getVehicleId();
		final Link startStationLink = scenario.getNetwork().getLinks().get( station.getLinkId() ) ;
		
		trip.add( createWalkLeg(currentLink, startStationLink, "walk_ow_sb", now) );

		// === car leg: ===
		
		endStationOW = findClosestAvailableParkingSpace(scenario.getNetwork().getLinks().get(route.getEndLinkId()));

		if (endStationOW == null) {

			this.setStateToAbort(now);
			this.basicAgentDelegate.getEvents().processEvent(new NoParkingSpaceEvent(now, route.getEndLinkId(), "ow"));

			return;
		}
		
		endStationOW.reserveParkingSpot();
		
		owcsVehicles.add(vehicleToBeUsed);
		station.removeCar(typeOfVehicle, vehicleToBeUsed);
		//this.carSharingVehicles.getOneWayVehicles().removeVehicle(typeOfVehicle, vehicleToBeUsed);

		final Link endStationLink = scenario.getNetwork().getLinks().get( endStationOW.getLinkId() ) ;

		trip.add(createCarLeg(startStationLink, endStationLink, "onewaycarsharing", owVehId, now));		

		//=== egress walk leg: ===
		
		trip.add( createWalkLeg(endStationLink, destinationLink, "walk_ow_sb", now) );

		//=== replacing the leg from routing with multiple-leg ===
		
		planElements.remove(this.basicAgentDelegate.getNextPlanElement());
		planElements.addAll(indexOfInsertion, trip);


	}

	private void insertRoundTripCarsharingTripWhenEndingActivity(double now) {

		LinkNetworkRouteImpl route = (LinkNetworkRouteImpl) ((Leg)this.basicAgentDelegate.getNextPlanElement()).getRoute();

		List<PlanElement> planElements = this.basicAgentDelegate.getCurrentPlan().getPlanElements();

		int indexOfInsertion = planElements.indexOf(this.basicAgentDelegate.getCurrentPlanElement()) + 1;

		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		Network network = scenario.getNetwork();
		final List<PlanElement> trip = new ArrayList<PlanElement>();
		
		Link startLink = network.getLinks().get(route.getStartLinkId());
		Link destinationLink = network.getLinks().get(route.getEndLinkId());
		
		if (hasCSVehicleAtLink(startLink.getId())) {

			//=== here we would need to check if he has the type of vehicle that he wants to use ===
			
			if (willUseTheVehicleLater(destinationLink.getId())) {

				//log.info("person will use the car later:" + basicAgentDelegate.getPerson().getId());
				String vehId = this.vehicleIdLocation.get(startLink).toString();

				trip.add(createCarLeg(startLink, destinationLink, "twowaycarsharing", vehId, now));				

			}

			else {
				// === agent is not using the car after this trip ===

				StationBasedVehicle currentVehicle = this.twcsVehicles.get(this.twcsVehicles.size() - 1);
				TwoWayCarsharingStation returnStation = 
						this.carSharingVehicles.getTwowaycarsharingstationsMap().get(currentVehicle.getStationId());
				Link endStationLink = network.getLinks().get(returnStation.getLinkId());
				
				String vehId = this.vehicleIdLocation.get(startLink.getId()).toString();
				
				if (!vehId.equals( currentVehicle.getVehicleId()))
					throw new RuntimeException("The ids of vehicles do not amtch. Aborting!");
				//=== add car leg to the end station: ===
				trip.add(createCarLeg(startLink, endStationLink, "twowaycarsharing", vehId, now));		
				
				// === add egress walk leg: ===
				trip.add( createWalkLeg(endStationLink, destinationLink, "walk_rb", now) );

			}

		}

		else {
			//=== agent does not have the carsharing vehicle at the current location===
			
			//=== find out which type of the vehicle the agent will need ===
			
			String typeOfVehicle = this.getDesiredVehicleType();			
			
			TwoWayCarsharingStation pickUpStation = this.findClosestAvailableTWCar(startLink.getId(), typeOfVehicle);
			
			if (pickUpStation == null) {
				this.setStateToAbort(now);
				this.basicAgentDelegate.getEvents().processEvent(new NoVehicleCarSharingEvent(now, startLink.getId(), "tw"));
				return;
			}
			
			Link startStationLink = network.getLinks().get(pickUpStation.getLinkId());
			
			//this.twcsVehicleIDs.add(pickUpStation.getVehicleIDs(typeOfVehicle).get(0));
			StationBasedVehicle vehicleToBeUsed = pickUpStation.getVehicles(typeOfVehicle).get(0);
			
			this.twcsVehicles.add(vehicleToBeUsed);
			
			trip.add( createWalkLeg(startLink, startStationLink, "walk_rb", now) );
			
			if (willUseTheVehicleLater(route.getEndLinkId())) {

				// === agent will use the vehicle after this trip ===				

				// === create a car leg of the trip: ===
				
				String vehId = vehicleToBeUsed.getVehicleId();
				pickUpStation.removeCar(typeOfVehicle, vehicleToBeUsed);
				//this.carSharingVehicles.getTwoWayVehicles().removeVehicle(typeOfVehicle, vehicleToBeUsed);
			
				trip.add(createCarLeg(startStationLink, destinationLink, "twowaycarsharing", vehId, now));
			}

			else {

				// === agent will not use the vehicle later (this a case when a carsharing trip is between the same locations) ===

				// === create a car leg of the trip: ===
				
				String vehId = vehicleToBeUsed.getVehicleId();
				pickUpStation.removeCar(typeOfVehicle, vehicleToBeUsed);

				//this.carSharingVehicles.getTwoWayVehicles().removeVehicle(typeOfVehicle, vehicleToBeUsed);
			
				trip.add(createCarLeg(startStationLink, startStationLink, "twowaycarsharing", vehId, now));
				
				trip.add( createWalkLeg(startStationLink, destinationLink, "walk_rb", now) );

			}
		}

		planElements.remove(this.basicAgentDelegate.getNextPlanElement());
		planElements.addAll(indexOfInsertion, trip);

	}
	
	private Leg createCarLeg(Link startLink, Link destinationLink, String mode, 
			String vehicleId, double now) {
		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		PopulationFactory pf = scenario.getPopulation().getFactory() ;
		RouteFactories routeFactory = ((PopulationFactory)pf).getRouteFactories() ;
		
		Vehicle vehicle = null ;
		Path path = this.pathCalculator.calcLeastCostPath(startLink.getToNode(), destinationLink.getFromNode(), 
				now, this.basicAgentDelegate.getPerson(), vehicle ) ;
		
		NetworkRoute carRoute = routeFactory.createRoute(NetworkRoute.class, startLink.getId(), destinationLink.getId() );
		carRoute.setLinkIds(startLink.getId(), NetworkUtils.getLinkIds( path.links), destinationLink.getId());
		carRoute.setTravelTime( path.travelTime );		
		
		carRoute.setVehicleId( Id.create( (vehicleId), Vehicle.class) ) ;

		Leg carLeg = pf.createLeg(mode);
		carLeg.setTravelTime( path.travelTime );
		carLeg.setRoute(carRoute);
		
		return carLeg;
	}
	
	private Leg createWalkLeg(Link startLink, Link destinationLink, String mode, double now) {
		
		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		PopulationFactory pf = scenario.getPopulation().getFactory() ;
		RouteFactories routeFactory = ((PopulationFactory)pf).getRouteFactories() ;
		
		Route routeWalk = routeFactory.createRoute( Route.class, startLink.getId(), destinationLink.getId() ) ; 
		
		final double egressDist = CoordUtils.calcEuclideanDistance(startLink.getCoord(), destinationLink.getCoord()) * beelineFactor;
		routeWalk.setTravelTime( (egressDist / walkSpeed));
		routeWalk.setDistance(egressDist);	

		final Leg walkLeg = pf.createLeg( mode );
		walkLeg.setRoute(routeWalk);

		return walkLeg;		
	}

	private boolean willUseTheVehicleLater(Id<Link> linkId) {
		boolean willUseVehicle = false;

		List<PlanElement> planElements = this.basicAgentDelegate.getCurrentPlan().getPlanElements();

		int index = planElements.indexOf(this.basicAgentDelegate.getCurrentPlanElement()) + 1;

		for (int i = index; i < planElements.size(); i++) {

			if (planElements.get(i) instanceof Leg) {

				if (((Leg)planElements.get(i)).getMode().equals("twowaycarsharing")) {

					if (((Leg)planElements.get(i)).getRoute().getStartLinkId().toString().equals(linkId.toString())) {

						willUseVehicle = true;
					}
				}

			}
		}

		return willUseVehicle;
	}

	private boolean hasCSVehicleAtLink(Id<Link> linkId) {
		boolean hasVehicle = false;

		if (this.vehicleIdLocation.containsKey(linkId))
			hasVehicle = true;

		return hasVehicle;
	}

	@Override
	public final void endLegAndComputeNextState(final double now) {

		parkCSVehicle( );			

		this.basicAgentDelegate.endLegAndComputeNextState(now);

	}	


	private void parkCSVehicle() {
		Leg currentLeg = (Leg) this.basicAgentDelegate.getCurrentPlanElement() ;

		if (currentLeg.getMode().equals("onewaycarsharing")) {
			StationBasedVehicle vehicleToBeReturned = owcsVehicles.get(owcsVehicles.size() - 1);
			vehicleToBeReturned.setStationId(endStationOW.getStationId());
			
			OneWayCarsharingStation returnStation = 
					this.carSharingVehicles.getOnewaycarsharingstationsMap().get(vehicleToBeReturned.getStationId());
			returnStation.addCar("car", vehicleToBeReturned);
			owcsVehicles.remove(vehicleToBeReturned);
		}
		else if (currentLeg.getMode().equals("walk_ow_sb") &&
				this.basicAgentDelegate.getNextPlanElement() instanceof Leg) {
			StationBasedVehicle vehicleToGet = owcsVehicles.get(owcsVehicles.size() - 1);

			OneWayCarsharingStation pickupStation = 
					this.carSharingVehicles.getOnewaycarsharingstationsMap().get(vehicleToGet.getStationId());
			
			pickupStation.freeParkingSpot();
			
		}
		else if (currentLeg.getMode().equals("twowaycarsharing") 

				&& this.basicAgentDelegate.getNextPlanElement() instanceof Leg
				) {

			if (((Leg)this.basicAgentDelegate.getNextPlanElement()).getMode().equals("walk_rb")) {
				this.vehicleIdLocation.remove(currentLeg.getRoute().getStartLinkId());
				
				StationBasedVehicle vehicleToBeReturned = this.twcsVehicles.get(this.twcsVehicles.size() - 1);
				
				TwoWayCarsharingStation returnStation = 
						this.carSharingVehicles.getTwowaycarsharingstationsMap().get(vehicleToBeReturned.getStationId());
								
				returnStation.addCar("car", 
						vehicleToBeReturned);

				this.twcsVehicles.remove(vehicleToBeReturned);
			}
		}
		else if (currentLeg.getMode().equals("twowaycarsharing")) {
			this.vehicleIdLocation.remove(currentLeg.getRoute().getStartLinkId());

			this.vehicleIdLocation.put(currentLeg.getRoute().getEndLinkId(), ((LinkNetworkRouteImpl)currentLeg.getRoute()).getVehicleId());
		}
		else if (currentLeg.getMode().equals("freefloating")) {
			Network network = this.basicAgentDelegate.getScenario().getNetwork();
			FFCSVehicle vehicleToBeReturned = this.ffVehicles.get(this.ffVehicles.size() - 1);
			Link endLink = network.getLinks().get(currentLeg.getRoute().getEndLinkId());
			Coord coord = endLink.getCoord();
			this.carSharingVehicles.getFfVehicleLocationQuadTree().put(coord.getX(), coord.getY(), vehicleToBeReturned);
			ffVehicles.remove(vehicleToBeReturned);
		}
	}

	//added methods

	private TwoWayCarsharingStation findClosestAvailableTWCar(Id<Link> linkId, String vehicleType) {
		
		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		Network network = scenario.getNetwork();
		TwoWayCarsharingConfigGroup twConfigGroup = (TwoWayCarsharingConfigGroup) 
				scenario.getConfig().getModule("TwoWayCarsharing");
		
		Link link = scenario.getNetwork().getLinks().get(linkId);
		double searchDistance = twConfigGroup.getsearchDistance();
		Collection<TwoWayCarsharingStation> location = 
				this.carSharingVehicles.getTwvehicleLocationQuadTree().getDisk(link.getCoord().getX(), 
						link.getCoord().getY(), searchDistance );
		if (location.isEmpty()) 
			return null;
		
		TwoWayCarsharingStation closest = null;
		for(TwoWayCarsharingStation station: location) {
			Coord coord = network.getLinks().get(station.getLinkId()).getCoord();

			if (CoordUtils.calcEuclideanDistance(link.getCoord(), coord) < searchDistance 
					&& station.getNumberOfVehicles(vehicleType) > 0) {
				closest = station;
				searchDistance = CoordUtils.calcEuclideanDistance(link.getCoord(), coord);
			}	
		}
		return closest;
	}	

	private FFCSVehicle findClosestAvailableCar(Link link) {
		FFCSVehicle vehicle = this.carSharingVehicles.getFfVehicleLocationQuadTree()
				.getClosest(link.getCoord().getX(), link.getCoord().getY());
		return vehicle;
	}

	private OneWayCarsharingStation findClosestAvailableOWCar(Link link, String vehicleType) {
		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		Network network = scenario.getNetwork();

		//find the closest available car and reserve it (make it unavailable)
		//if no cars within certain radius return null
		final OneWayCarsharingConfigGroup owConfigGroup = (OneWayCarsharingConfigGroup)
				scenario.getConfig().getModule("OneWayCarsharing");
		double distanceSearch = owConfigGroup.getsearchDistance() ;

		Collection<OneWayCarsharingStation> location = 
				this.carSharingVehicles.getOwvehicleLocationQuadTree().getDisk(link.getCoord().getX(), 
						link.getCoord().getY(), distanceSearch);
		if (location.isEmpty()) return null;

		OneWayCarsharingStation closest = null;
		for(OneWayCarsharingStation station: location) {
			
			Coord coord = network.getLinks().get(station.getLinkId()).getCoord();
			
			if (CoordUtils.calcEuclideanDistance(link.getCoord(), coord) < distanceSearch 
					&& station.getNumberOfVehicles(vehicleType) > 0) {
				closest = station;
				distanceSearch = CoordUtils.calcEuclideanDistance(link.getCoord(), coord);
			}
		}	
		return closest;
	}

	private OneWayCarsharingStation findClosestAvailableParkingSpace(Link link) {
		Scenario scenario = this.basicAgentDelegate.getScenario() ;
		Network network = scenario.getNetwork();

		//find the closest available parking space and reserve it (make it unavailable)
		//if there are no parking spots within search radius, return null
		OneWayCarsharingConfigGroup owConfigGroup = (OneWayCarsharingConfigGroup) 
				scenario.getConfig().getModule("OneWayCarsharing");
		
		double distanceSearch = owConfigGroup.getsearchDistance();

		Collection<OneWayCarsharingStation> location = 
				this.carSharingVehicles.getOwvehicleLocationQuadTree().getDisk(link.getCoord().getX(), 
						link.getCoord().getY(), distanceSearch);
		if (location.isEmpty()) return null;

		OneWayCarsharingStation closest = null;
		for(OneWayCarsharingStation station: location) {
			
			Coord coord = network.getLinks().get(station.getLinkId()).getCoord();
			
			if (CoordUtils.calcEuclideanDistance(link.getCoord(), coord) < distanceSearch 
					&& station.getAvaialbleParkingSpots() > 0) {
				closest = station;
				distanceSearch = CoordUtils.calcEuclideanDistance(link.getCoord(), coord);
			}
		}
		return closest;
	}
	
	private String getDesiredVehicleType() {
		
		return "car";
	}
	
	//the end of added methods	

	void resetCaches() {
		WithinDayAgentUtils.resetCaches(this.basicAgentDelegate);
	}

	@Override
	public final Id<Vehicle> getPlannedVehicleId() {
		PlanElement currentPlanElement = this.getCurrentPlanElement();
		NetworkRoute route = (NetworkRoute) ((Leg) currentPlanElement).getRoute(); // if casts fail: illegal state.


		if (route.getVehicleId() != null) 
			return route.getVehicleId();

		else
			return Id.create(this.getId(), Vehicle.class); // we still assume the vehicleId is the agentId if no vehicleId is given.

	}

	// ####################################################################
	// only pure delegate methods below this line

	@Override
	public final PlanElement getCurrentPlanElement() {
		return this.basicAgentDelegate.getCurrentPlanElement() ;
	}

	@Override
	public final PlanElement getNextPlanElement() {
		return this.basicAgentDelegate.getNextPlanElement() ;
	}

	@Override
	public final void setVehicle(final MobsimVehicle veh) {
		this.basicAgentDelegate.setVehicle(veh) ;
	}

	@Override
	public final MobsimVehicle getVehicle() {
		return this.basicAgentDelegate.getVehicle() ;
	}

	@Override
	public final double getActivityEndTime() {
		return this.basicAgentDelegate.getActivityEndTime() ;
	}

	@Override
	public final Id<Link> getCurrentLinkId() {
		return this.driverAgentDelegate.getCurrentLinkId() ;
	}

	@Override
	public final Double getExpectedTravelTime() {
		return this.basicAgentDelegate.getExpectedTravelTime() ;

	}

	@Override
	public Double getExpectedTravelDistance() {
		return this.basicAgentDelegate.getExpectedTravelDistance() ;
	}

	@Override
	public final String getMode() {
		return this.basicAgentDelegate.getMode() ;
	}

	@Override
	public final Id<Link> getDestinationLinkId() {
		return this.basicAgentDelegate.getDestinationLinkId() ;
	}

	@Override
	public final Person getPerson() {
		return this.basicAgentDelegate.getPerson() ;
	}

	@Override
	public final Id<Person> getId() {
		return this.basicAgentDelegate.getId() ;
	}

	@Override
	public final Plan getCurrentPlan() {
		return this.basicAgentDelegate.getCurrentPlan() ;
	}

	@Override
	public boolean getEnterTransitRoute(final TransitLine line, final TransitRoute transitRoute, final List<TransitRouteStop> stopsToCome, TransitVehicle transitVehicle) {
		return this.transitAgentDelegate.getEnterTransitRoute(line, transitRoute, stopsToCome, transitVehicle) ;
	}

	@Override
	public boolean getExitAtStop(final TransitStopFacility stop) {
		return this.transitAgentDelegate.getExitAtStop(stop) ;
	}

	@Override
	public double getWeight() {
		return this.transitAgentDelegate.getWeight() ;
	}

	@Override
	public Id<TransitStopFacility> getDesiredAccessStopId() {
		return this.transitAgentDelegate.getDesiredAccessStopId() ;
	}

	@Override
	public Id<TransitStopFacility> getDesiredDestinationStopId() {
		return this.transitAgentDelegate.getDesiredAccessStopId() ;
	}

	@Override
	public boolean isWantingToArriveOnCurrentLink() {
		return this.driverAgentDelegate.isWantingToArriveOnCurrentLink() ;
	}

	@Override
	public MobsimAgent.State getState() {
		return this.basicAgentDelegate.getState() ;
	}
	@Override
	public final void setStateToAbort(final double now) {
		this.basicAgentDelegate.setStateToAbort(now);
	}

	@Override
	public final void notifyArrivalOnLinkByNonNetworkMode(final Id<Link> linkId) {
		this.basicAgentDelegate.notifyArrivalOnLinkByNonNetworkMode(linkId);
	}

	@Override
	public final void notifyMoveOverNode(Id<Link> newLinkId) {
		this.driverAgentDelegate.notifyMoveOverNode(newLinkId);
	}

	@Override
	public Id<Link> chooseNextLinkId() {
		return this.driverAgentDelegate.chooseNextLinkId() ;
	}

	public Facility<? extends Facility<?>> getCurrentFacility() {
		return this.basicAgentDelegate.getCurrentFacility();
	}

	public Facility<? extends Facility<?>> getDestinationFacility() {
		return this.basicAgentDelegate.getDestinationFacility();
	}

	public final PlanElement getPreviousPlanElement() {
		return this.basicAgentDelegate.getPreviousPlanElement();
	}



}
