package edu.hawaii.kra.kew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.kuali.kra.bo.Unit;
import org.kuali.kra.infrastructure.KraServiceLocator;
import org.kuali.kra.service.UnitService;
import org.kuali.rice.coreservice.framework.CoreFrameworkServiceLocator;

import com.google.common.collect.Lists;

public class UhUnitToBranchDecoder {
	
    // For performance this could be made into a singleton but this would require a way to flush it
    // if the parameter is changed.  So for now I am allowing this to get built every time it is needed.
    private org.apache.commons.logging.Log LOG = org.apache.commons.logging.LogFactory.getLog(UhUnitToBranchDecoder.class);
    private HashMap<String,String>unitToBranch;
  
	private HashMap<String, String> getUnitToBranchMap() {

		if (unitToBranch == null) {
			unitToBranch = new HashMap<String, String>();
			String branch_units_param = CoreFrameworkServiceLocator
					.getParameterService().getParameterValueAsString("KC-GEN",
							"All", "uh_pd_routing_branch_units");
			if (branch_units_param != null && !branch_units_param.isEmpty()) {
				// Format should be BRANCH:UNIT,...;...
				// Split ; delimited list of branch list pairs. IE
				// Branch:UNITLIST;...
				ArrayList<String> branch_units = new ArrayList<String>(
						Arrays.asList(branch_units_param.split(";")));
				for (String branch_unit : branch_units) {
					// Split : separated Branch:unit_list pairs
					String[] branchUnitsPair = branch_unit.split(":");
					if (branchUnitsPair.length != 2) {
						LOG.error("uh_pd_routing_branch_units configuration error branch:units pair doesn't have 2 : seperated elements");
					} else {
						// Process each pair of branch and comma separated unit
						// lists into HashMap Key=Unit Value=Branch
						String branch = branchUnitsPair[0];
						ArrayList<String> units = new ArrayList<String>(
								Arrays.asList(branchUnitsPair[1].split(",")));
						for (String unit : units) {
							unitToBranch.put(unit, branch);
						}
					}
				}
			} else {
				LOG.error("uh_pd_routing_branch_units param not configured");
			}
		}
		return unitToBranch;
	}
    
    public String findUnitBranchName(String unitNumber) {
        // If unit not found assume UHMANOA branch name
        String branch="UHMANOA";
        if (unitNumber!=null) {
        	List<Unit> units = KraServiceLocator.getService(UnitService.class).getUnitHierarchyForUnit(unitNumber);
        	if (CollectionUtils.isNotEmpty(units)) {
                for(Unit unit: Lists.reverse(units)) {
                	String branchFound=getUnitToBranchMap().get(unit.getUnitNumber());
                	if (branchFound != null) {
                		branch=branchFound;
                		break;  // Break so we don't walk up to 000001 and always return UHMANOA
                	}
                }
        	}
        }
        return branch;
    }
}
