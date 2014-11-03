package edu.hawaii.proposaldevelopment.document.workflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jdom.Document;
import org.jdom.Element;
import org.kuali.kra.bo.Unit;
import org.kuali.kra.bo.UnitAdministrator;
import org.kuali.kra.infrastructure.KraServiceLocator;
import org.kuali.kra.proposaldevelopment.bo.ProposalPersonUnit;
import org.kuali.kra.service.UnitService;
import org.kuali.rice.core.api.util.xml.XmlHelper;
import org.kuali.rice.kew.api.identity.Id;
import org.kuali.rice.kew.api.identity.PrincipalId;
import org.kuali.rice.kew.api.rule.RoleName;
import org.kuali.rice.kew.engine.RouteContext;
import org.kuali.rice.kew.routeheader.DocumentContent;
import org.kuali.rice.kew.rule.GenericRoleAttribute;
import org.kuali.rice.kew.rule.QualifiedRoleName;
import org.kuali.rice.kew.rule.ResolvedQualifiedRole;
import org.kuali.rice.kew.workgroup.WorkflowGroupId;
import org.kuali.rice.kim.api.group.Group;
import org.kuali.rice.kim.api.group.GroupService;
import org.kuali.rice.kim.api.identity.Person;
import org.kuali.rice.kim.api.identity.PersonService;
import org.kuali.rice.krad.service.BusinessObjectService;

import edu.hawaii.kra.bo.UhPersonDelegate;
import edu.hawaii.kra.kew.UhUnitToBranchDecoder;

/**
 * @author Ronald Gouldner (Based on code shared by Omar Soto)
 * UH 5.1.1 Routing
 */
@SuppressWarnings("unchecked")
public class UhUnitAdministratorRoleAttribute extends GenericRoleAttribute {
	private static final long serialVersionUID = 2187135270043141363L;

	@SuppressWarnings("unused")
	private static final Log LOG = LogFactory.getLog(UhUnitAdministratorRoleAttribute.class);
	private static final String KEYPERSONUNIT = "unitNumber";

	// Default Values 
	private boolean getHomeUnit = true;
	private List<String> roleNameStrings;
	
	//private List<String> unitIds;
	//private List<String> unitNames;
	private List<String> qualifiedRoleNames;
	private HashMap<String,String> unitNameToCode;
 	private HashMap<String,String> qualifiedRoleNameAdminCodes;
	private HashMap<String,String> qualifiedRoleNameUnitCodes;
	private HashMap<String,List<Unit>> unitHierarchyMap;
	private HashMap<String,String> qualifiedRoleToAnnotation;
	private UhUnitToBranchDecoder unitToBranch;
	
	private HashMap<String,String> branchToLabel;
	private HashMap<String,String> branchToAdminCodes;
	
	// Options Map
	// REQ : Y=Yes N=No
    boolean required;
    
	UnitService unitService;
	BusinessObjectService businessObjectService;
	
	public void processOptions(String options) {
		List<String> optionsPairs=Arrays.asList(options.split(";"));
		
		for( String optionPair : optionsPairs) {
			String[] option = optionPair.split("=");
			if (option.length == 2) {
				if (option[0].toLowerCase().equals("req")) {
	                required = option[1].toLowerCase().equals("y");
				} else {
	             	LOG.warn("UhUnitAdministratorRoleAttribute Configured incorrectly. Unknown Option");
				}
			} else {
				LOG.warn("UhUnitAdministratorRoleAttribute Configured incorrectly. Option format [option=val]");
			}
		}
		
	}
	
	private boolean processNodeConfig(String config) {
		// format = BRANCH(Comma separated):Name:UnitAdminCodes(comma separated)|...
		boolean configOK = false;
		
		branchToLabel = new HashMap<String,String>();
		branchToAdminCodes = new HashMap<String,String>();
		
		// Note: splitting on | requires escaping or it splits all chars
		ArrayList<String> branchConfigs = new ArrayList<String>(Arrays.asList(config.split("\\|")));
		for (String branchConfig : branchConfigs) {
			String[] branchConfigItems = branchConfig.split(":");
			if (branchConfigItems.length == 3) {
			    String branchNames = branchConfigItems[0];
			    String adminLabel = branchConfigItems[1];
			    String adminCodes = branchConfigItems[2];
			    //  Config allows for comma delimited Branch Names for branchs which take same label/admin codes
			    ArrayList<String> branchNamesArray = new ArrayList<String>(Arrays.asList(branchNames.split(",")));
			    for (String branchName : branchNamesArray) {
			        branchToLabel.put(branchName,adminLabel);
			        branchToAdminCodes.put(branchName,adminCodes);
			        configOK=true;
			    }
			} else {
				// Don't return Config OK false since we may have been able to process other config items and no need to 
				// lose them.
				LOG.warn("Configuration error.  Branch Config Item does not have 3 arguments [" + branchConfig + "]");
			}
		}
		
		return configOK;
	}
	
	
	
	public List<String> getQualifiedRoleNames(String roleNameInput,
			DocumentContent documentContent) {
		
		// Create new qualifiedRoleNamesObject
		roleNameStrings = new ArrayList<String>();
		this.qualifiedRoleNames = new ArrayList<String>();
		this.qualifiedRoleNameAdminCodes = new HashMap<String,String>();
		this.qualifiedRoleNameUnitCodes = new HashMap<String,String>();
		this.qualifiedRoleToAnnotation = new HashMap<String,String>();
		
		// Determine Units Involved with this PD document
		loadUnitRequiredApprovals(documentContent.getRouteContext());
		
		// Parse roleNameInput
		// [Options]:[Roles]
		// Options opt=val;opt=val...
		// Roles   Role;AdminCode!Role;AdminCode...
		String configMsg="UhUnitAdministratorRoleAttribute Configured incorrectly. ";
		if (roleNameInput != null) {
			String[] inputSplit = roleNameInput.split("!");

			if (inputSplit.length == 3) {
				processOptions(inputSplit[0]);
				String roleName = inputSplit[1];
				if (processNodeConfig(inputSplit[2])) {
					for (String unitName : unitNameToCode.keySet()) {
						// Process each unit and determine if it's branch has
						// admin codes configured.

						String branch = getUhUnitToBranchDecoder()
								.findUnitBranchName(
										unitNameToCode.get(unitName));
						String adminCodes = branchToAdminCodes.get(branch);
						String label = branchToLabel.get(branch);
						if (adminCodes == null) {
							// Branch not configured try for DEFAULT
							adminCodes = branchToAdminCodes.get("DEFAULT");
							label = branchToLabel.get("DEFAULT");
						}
						// If we have valid admin codes then create qualified
						// role for unit
						// Note: Admin Codes = 0 check is admin code to represent SKIP step
						//       it is only needed if there was also a DEFAULT
						//       to prevent default from being used for units which
						//       should be skipped.  Therefore if default is skip then 
						//       neither default nor skip are necessary as there will be no match
						//       which also results in a skip
						if (adminCodes != null && !adminCodes.equals("0")) {
							String qualifiedRole =  label + " ; " + unitName;
							this.qualifiedRoleNames.add(qualifiedRole);
							// Store name/code and name/unit pair so we can
							// lookup the correct code for the named role in
							// resolveRecipients
							this.qualifiedRoleNameAdminCodes.put(qualifiedRole,
									adminCodes);
							this.qualifiedRoleNameUnitCodes.put(qualifiedRole,
									unitNameToCode.get(unitName));
							if (!roleNameStrings.contains(roleName)) {
								roleNameStrings.add(roleName);
							}
						}
					}
				}
			}
		}
		
		return this.qualifiedRoleNames;
	}
	
	public List<RoleName> getRoleNames() {
		//
		List<RoleName> rollNames = new ArrayList<RoleName>();
		
		for (String roleName : roleNameStrings) {
			RoleName role = RoleName.Builder.create("edu.hawaii.proposaldevelopment.document.workflow.UhUnitAdministratorRoleAttribute", roleName, roleName).build();
			rollNames.add(role);
		}
		return rollNames;
	}
	
	@Override
	public Map<String, String> getProperties() {
		// intentionally unimplemented...not intending on using this attribute
		// client-side
		return null;
	}
	
    private UnitService getUnitService() {
    	if (this.unitService == null) {
    		this.unitService = KraServiceLocator.getService(UnitService.class);
    	}
    	
        return unitService;
    }
    
    protected UhUnitToBranchDecoder getUhUnitToBranchDecoder() {
    	if (this.unitToBranch == null) {
    		unitToBranch = new UhUnitToBranchDecoder();
    	}
    	return unitToBranch;
    }
    
    public BusinessObjectService getBusinessObjectService() {
    	if (this.businessObjectService == null) {
    		this.businessObjectService = KraServiceLocator.getService(BusinessObjectService.class);
    	}
        return businessObjectService;
    }
	
    @SuppressWarnings("unchecked")
    public List<UnitAdministrator> retrieveUnitAdministratorsByUnitNumberAndAdminCode(String unitNumber,String adminCode) {
        Map<String, String> queryMap = new HashMap<String, String>();
        queryMap.put("unitNumber", unitNumber);
        queryMap.put("unitAdministratorTypeCode", adminCode);
        List<UnitAdministrator> unitAdministrators = 
            (List<UnitAdministrator>) getBusinessObjectService().findMatching(UnitAdministrator.class, queryMap);
        List<UnitAdministrator> activeUnitAdministrators = new ArrayList<UnitAdministrator>();
        for (UnitAdministrator unitAdmin : unitAdministrators) {
        	if (unitAdmin.isActive()) {
        		activeUnitAdministrators.add(unitAdmin);
        	}
        }
        return activeUnitAdministrators;
    }
    
    public List<UhPersonDelegate> retreivePersonDelegates(PrincipalId personId) {
        Map<String, String> queryMap = new HashMap<String, String>();
        queryMap.put("delegatorPersonId", personId.getPrincipalId());
        List<UhPersonDelegate> uhPersonDelegateList = 
            (List<UhPersonDelegate>) getBusinessObjectService().findMatching(UhPersonDelegate.class, queryMap);
        return uhPersonDelegateList;
    }
    
    
    protected List<Id> getUnitAdmins(String unitNumber, String adminCode, QualifiedRoleName qualifiedRoleName) {
    	List<Id> admins;
    	
    	PrincipalId personId; 
    	String annotation=null;
    	

	    List<UnitAdministrator> unitAdministrators = retrieveUnitAdministratorsByUnitNumberAndAdminCode(unitNumber,adminCode);
	    
    	// Note: Original version was coded recursive to act like descends hierarchy. 
    	//       However this is problematic without an option so we changed our mind and left
    	//       unit admin unit specific.   Commenting out the code so we can add back in if
    	//       we decide to add a "descends" flag to unit admin table.
        /*
	    if (unitAdministrators.size() == 0 && !unitNumber.equals("000001")) {
	    	// recursive call to get from parent if not found and not at top yet
	    	admins = getUnitAdmins(getUnitService().getUnit(unitNumber).getParentUnitNumber(), adminCode, qualifiedRoleName);
	    } else {
	    */
	    	admins = new ArrayList<Id>();
	        for ( UnitAdministrator unitAdministrator : unitAdministrators ) {
	    	    personId = new PrincipalId(unitAdministrator.getPersonId());
	    	    Person adminPerson = KraServiceLocator.getService(PersonService.class).getPerson(personId.getId());
	    	    if (adminPerson.isActive()) {
	    	    	List<UhPersonDelegate> uhPersonDelegateList = retreivePersonDelegates(personId);
	    	        String annotationDesc;
	    	        // KC-747 Inactive users are appearing in new routing
	    	        // Remove inactive delegations or delegations to inactive people
	    	        // Note: this may degrade efficiency but not sure how to restrict delegates during find
	    		    //       since null means always match.  So isActive method knows how to correctly
	    		    //       check for null From/To dates and check if today is in range.
	    	        //       it also checks to make sure the person was not inactive
	    	        List<UhPersonDelegate> activeUhPersonDelegateList = new ArrayList<UhPersonDelegate>();
	    	        for ( UhPersonDelegate delegate : uhPersonDelegateList) {
	    	        	if (delegate.isActive()) {
	    	        		activeUhPersonDelegateList.add(delegate);
	    	        	}
	    	        }
	    	        //  Now process the active list of delegates
	    	        if (activeUhPersonDelegateList.size() > 0) {
	    	      	    for ( UhPersonDelegate delegate : activeUhPersonDelegateList) {
	    	    			// If delegate is configured to include Delegator add original personId to list
	    	    			if (delegate.includeDelegator()) {
	    	    				if (!admins.contains(personId)) {
	    	    					admins.add(personId);
	    	    				} 
	    	    				annotationDesc = " delegate in addition to ";
	    	    			} else {
	    	    				annotationDesc = " delegate on behalf of ";
	    	    			}
	    	    		
	    	    			PrincipalId delegateId = new PrincipalId(delegate.getDelegateePersonId());
	    	    			String delegatorName = delegate.getDelegatorPerson().getFullName();
	    	    			String delegateeName = delegate.getDelegateePerson().getFullName();
	    	    			String newAnnotation = new String("(" + delegateeName + annotationDesc + delegatorName + ")");
	    	    			if (!admins.contains(delegateId)) {
	    	    				admins.add(delegateId);
	    	    			}
	    	    			if (annotation == null) {
	    	    				annotation = newAnnotation;
	    	    			} else {
	    	    				// Don't add annotation if it was already added (Duplicate delegation, duplicate unit admin config, etc)
	    	    				// Originally I just didn't add annotation when I decided not to add delegate however I realized that
	    	    				// two people could assign same delegate.  In that case second delegate instance will not be added but
	    	    				// second annotation should be.
	    	    				if (!annotation.contains(newAnnotation)) {
	    	    					annotation = annotation + ", " + newAnnotation; 
	    	    				}
	    	    			}
	    	    	    }
	    	        } else {
	    	            if (!admins.contains(personId)) {
	    	  	            admins.add(personId);
	    	            }
	    	        }
	    	    }
	        }
	    /*    
	    }
	    */
    	
	    if (annotation != null) {
	        qualifiedRoleToAnnotation.put(qualifiedRoleName.toString(),annotation);
	    }
	    
    	return admins;
    }
    
    /**
     * Template method that delegates to {@link #resolveRecipients(RouteContext, QualifiedRoleName)} and
     * {@link #getLabelForQualifiedRoleName(QualifiedRoleName)
     */
    @Override
    protected ResolvedQualifiedRole resolveQualifiedRole(RouteContext routeContext, QualifiedRoleName qualifiedRoleName)
    {
    	String nodeInstanceBranchName = routeContext.getNodeInstance().getBranch().getName();
    	
        List<Id> recipients = resolveRecipients(routeContext, qualifiedRoleName, nodeInstanceBranchName);
        
    	String annotation=qualifiedRoleToAnnotation.get(qualifiedRoleName.toString());
    	
        ResolvedQualifiedRole rqr = new ResolvedQualifiedRole(getLabelForQualifiedRoleName(qualifiedRoleName), recipients); // default to no annotation...
        if (annotation !=null) {
            rqr.setAnnotation(annotation);
        }
        return rqr;
    }
    
	protected List<Id> resolveRecipients(RouteContext routeContext, QualifiedRoleName qualifiedRoleName, String branch) {
		// I think this call may not be needed.  But doesn't hurt since it does nothing if already done.
		loadUnitRequiredApprovals(routeContext);
		
		List<Id> members = new ArrayList<Id>();
		String unitNumber = this.qualifiedRoleNameUnitCodes.get(qualifiedRoleName.getBaseRoleName());
		String adminCodes = this.qualifiedRoleNameAdminCodes.get(qualifiedRoleName.getBaseRoleName());
		
		

		// Parse admin codes splitting on comma
		List<Id> adminCodeMembers;
		ArrayList<String> adminCodesArray = new ArrayList<String>(Arrays.asList(adminCodes.split(",")));
		for (String adminCode : adminCodesArray) {
			adminCodeMembers = getUnitAdmins(unitNumber, adminCode, qualifiedRoleName);
			if (!adminCodeMembers.isEmpty()) {
				members.addAll(adminCodeMembers);
			}
		}

		
		// If no members and members are required then add help desk to
		// resolve the lack of
		// unit admin members
		if (members.size() == 0 && required) {
			GroupService groupService = KraServiceLocator
					.getService(GroupService.class);
			Group helpdesk = groupService.getGroupByNamespaceCodeAndName(
					"KC-WKFLW", "myGRANT Help Desk");
			long helpDeskId = Long.parseLong(helpdesk.getId());
			members.add(new WorkflowGroupId(helpDeskId));
			qualifiedRoleToAnnotation.put(qualifiedRoleName.toString(),"Routing not assigned.  Please contact the help desk as 808-956-5198.");
		}
	    
		return members;
	}
	
	protected List<Unit> getUnitHierarchy(String unitNumber) {
		List<Unit> hierarchyList;
		
		// Maintain a cache for performance
		if (unitHierarchyMap==null) {
			unitHierarchyMap = new HashMap<String,List<Unit>>();
		}
		
		
		if (unitHierarchyMap.containsKey(unitNumber)) { 
			hierarchyList = unitHierarchyMap.get(unitNumber);
		} else {
			hierarchyList = getUnitService().getUnitHierarchyForUnit(unitNumber);
			unitHierarchyMap.put(unitNumber,hierarchyList);
		} 
		return  hierarchyList;
	}
	
	protected void loadUnitRequiredApprovals(RouteContext routeContext) {
		if (unitNameToCode == null) {
			// Build new unitNameCode HashMap
			unitNameToCode = new HashMap<String,String>();
			
			Collection<Element> units = retrieveKeyPersonnelUnits(routeContext);
			String keypersonUnit;
	
			for(Element unit : units) {
				keypersonUnit = unit.getChildText(KEYPERSONUNIT);	
				if (!unitNameToCode.containsKey(keypersonUnit)) {
					String unitName=getUnitService().getUnit(keypersonUnit).getUnitName();
					unitNameToCode.put(unitName, keypersonUnit);
				}
			}
		}
	}
	
	@SuppressWarnings({ "unchecked", "unchecked" })
	private Collection<Element> retrieveKeyPersonnelUnits(RouteContext context) {		
	    Document document = XmlHelper.buildJDocument(context.getDocumentContent().getDocument());
	   
	    Collection<Element> units = XmlHelper.findElements(document.getRootElement(), ProposalPersonUnit.class.getName());
	    return units;
	}	
}
