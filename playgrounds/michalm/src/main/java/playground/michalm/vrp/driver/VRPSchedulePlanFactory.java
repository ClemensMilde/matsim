package playground.michalm.vrp.driver;

import org.matsim.api.core.v01.population.Plan;

import pl.poznan.put.vrp.dynamic.data.model.Vehicle;
import playground.michalm.dynamic.*;
import playground.michalm.vrp.data.MATSimVRPData;


public class VRPSchedulePlanFactory
    implements DynPlanFactory
{
    private Vehicle vehicle;
    private MATSimVRPData data;


    public VRPSchedulePlanFactory(Vehicle vehicle, MATSimVRPData data)
    {
        this.vehicle = vehicle;
        this.data = data;
    }


    @Override
    public Plan create(DynAgent agent)
    {
        return new VRPSchedulePlan(vehicle, data);
    }
}
