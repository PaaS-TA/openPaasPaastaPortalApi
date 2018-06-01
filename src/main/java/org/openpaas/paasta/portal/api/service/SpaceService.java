package org.openpaas.paasta.portal.api.service;

import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.v2.spaces.*;
import org.cloudfoundry.client.v2.users.UserResource;
import org.cloudfoundry.operations.useradmin.ListSpaceUsersRequest;
import org.cloudfoundry.operations.useradmin.SpaceUsers;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.openpaas.paasta.portal.api.common.Common;
import org.openpaas.paasta.portal.api.model.Org;
import org.openpaas.paasta.portal.api.model.Space;
import org.openpaas.paasta.portal.api.model.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//import org.openpaas.paasta.portal.api.mapper.cc.OrgMapper;
//import org.openpaas.paasta.portal.api.mapper.cc.SpaceMapper;

/**
 * 공간 서비스 - 공간 목록 , 공간 이름 변경 , 공간 생성 및 삭제 등을 제공한다.
 *
 * @author 조민구
 * @version 1.0
 * @since 2016.4.4 최초작성
 */
@Service
public class SpaceService extends Common {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpaceService.class);

    @Autowired
    private AsyncUtilService asyncUtilService;

    @Autowired
    private UserService userService;

    @Autowired
    @Lazy // To resolve circular reference
    private OrgService orgService;

    @Autowired
    private PasswordGrantTokenProvider adminTokenProvider;

//    @Autowired
//    private SpaceMapper spaceMapper;
//    @Autowired
//    private OrgMapper orgMapper;

    /**
     * 공간(스페이스) 목록 조회한다.
     * 특정 조직을 인자로 받아 해당 조직의 공간을 조회한다.
     *
     * @param token the token
     * @return ListSpacesResponse
     * @author hgcho
     * @version 2.0
     * @since 2018.5.3
     */
    public ListSpacesResponse getSpaces(String orgId, String token) {
        ListSpacesResponse response = Common
            .cloudFoundryClient( connectionContext(), tokenProviderWithDefault( token, adminTokenProvider ) ).spaces()
            .list( ListSpacesRequest.builder().organizationId( orgId ).build() ).block();

        return response;
    }

    public ListSpacesResponse getSpacesWithOrgName(String orgName, String token) {
        final String orgId = orgService.getOrgId( orgName, token );

        return getSpaces( orgId, token );
    }

    /**
     * 공간(스페이스) 목록 조회한다.
     * 특정 조직을 인자로 받아 해당 조직의 공간을 조회한다.
     *
     * @param org   the org
     * @param token the token
     * @return ListSpacesResponse
     * @author hgcho
     * @version 2.0
     * @since 2018.5.3
     */
    public ListSpacesResponse getSpaces(Org org, String token) {
        String orgId = null;
        if (org.getGuid() != null) {
            orgId = org.getGuid().toString();
        } else if (org.getName() != null) {
            orgId = orgService.getOrgId( org.getName(), token );
        } else {
            throw new CloudFoundryException( HttpStatus.BAD_REQUEST, "To get spaces in org, you must be require org name or org id." );
        }

        Objects.requireNonNull( orgId, "Org id must not be null." );
        ListSpacesResponse response = Common
            .cloudFoundryClient( connectionContext(), tokenProvider( token ) ).spaces()
            .list( ListSpacesRequest.builder().organizationId( orgId ).build() ).block();

        return response;
    }

    private static final List<String> SPACE_ROLES_FOR_ORGMANAGER = Arrays.asList(
        "SpaceAuditor", "SpaceDeveloper", "SpaceManager" );
    private static final List<String> SPACE_ROLES_FOR_ORGAUDITOR = Arrays.asList(
        "SpaceAuditor", "SpaceDeveloper" );
    private static final List<String> SPACE_ROLES_FOR_BILLINGMANAGER = Arrays.asList(
        "SpaceAuditor" );

    /**
     * 공간을 생성한다. (Space : Create)
     *
     * @param space the space
     * @param token the token
     * @return boolean boolean
     * @throws Exception the exception
     * @author hgcho
     * @version 2.0
     * @since 2018.5.3
     */
    public CreateSpaceResponse createSpace(Space space, String token) {
        Objects.requireNonNull( space.getSpaceName(), "Space name must not be null. Required request body is space name(spaceName) and org GUID (orgGuid)." );
        Objects.requireNonNull( space.getOrgGuid(), "Space name must not be null. Required request body is space name(spaceName) and org GUID (orgGuid)." );

        final CreateSpaceResponse response =
            Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
                .spaces().create( CreateSpaceRequest.builder()
                .name( space.getSpaceName() ).organizationId( space.getOrgGuid() ).build() )
                .block();

        // Results for association roles will be disposed
        associateSpaceUserRolesByOrgIdAndRole(response.getMetadata().getId(), space.getOrgGuid() );

        return response;
    }

    /**
     * 공간의 정보를 가져온다. (Space : Read)
     *
     * @param spaceId
     * @param token
     * @return GetSpaceResponse
     * @author hgcho
     * @version 2.0
     * @since 2018.5.3
     */
    public GetSpaceResponse getSpace(String spaceId, String token) {
        return Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
            .spaces().get( GetSpaceRequest.builder().spaceId( spaceId ).build() ).block();
    }

    /*
    public void getSpaceId(String orgName, String spaceName, String token) {
        return Common.cloudFoundryOp
            .spaces().get( GetSpaceRequest.builder()..build() )
    }
    */

    public SpaceResource getSpaceUsingName( String orgName, String spaceName, String token ) {
        final TokenProvider internalTokenProvider;
        if ( null != token && !"".equals( token ) )
            internalTokenProvider = tokenProvider( token );
        else
            internalTokenProvider = adminTokenProvider;


        final ListSpacesResponse response = this.getSpacesWithOrgName( orgName, token );
        if (response.getTotalResults() <= 0)
            return null;
        else if (response.getResources() != null && response.getResources().size() <= 0)
            return null;

        List<SpaceResource> spaces = response.getResources().stream()
            .filter( resource -> spaceName.equals( resource.getEntity().getName() ) )
            .collect(Collectors.toList());
        if (spaces.size() <= 0)
            return null;

        return spaces.get( 0 );
    }

    /**
     * 공간명을 변경한다. (Space : Update)
     *
     * @param space the space
     * @param token the token
     * @return boolean boolean
     * @throws Exception the exception
     * @author hgcho
     * @version 2.0
     * @since 2018.5.3
     */
    public UpdateSpaceResponse renameSpace(Space space, String token) throws Exception{
        String spaceGuid = space.getGuid().toString();
        String newSpaceName = space.getNewSpaceName();
        Objects.requireNonNull( spaceGuid, "Space GUID(guid) must be not null. Request body is made space GUID(guid) and new space name(newSpaceName)." );
        Objects.requireNonNull( newSpaceName, "New space name must be not null. Request body is made space GUID(guid) and new space name(newSpaceName)." );
        if(!stringNullCheck(spaceGuid,newSpaceName)) {
            throw new CloudFoundryException(HttpStatus.BAD_REQUEST, "Bad Request", "Required request body content(guid or newSpaceName) is missing.");
        }

        return Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
            .spaces().update( UpdateSpaceRequest.builder().spaceId( spaceGuid )
                .name( newSpaceName ).build() )
            .block();
    }

    /**
     * 공간을 삭제한다. (Space : Delete)
     *
     * @param space the space
     * @param token the token
     * @return boolean boolean
     * @throws Exception the exception
     * @author hgcho
     * @version 2.0
     * @since 2018.5.3
     */
    public DeleteSpaceResponse deleteSpace(Space space, String token) {
        Objects.requireNonNull( space.getGuid(), "Space GUID must not be null. Require parameters; spaceGuid[, recursive]" );

        String spaceGuid = space.getGuid().toString();
        boolean recursive = space.isRecursive();
        if ( !stringNullCheck( spaceGuid ) ) {
            throw new CloudFoundryException( HttpStatus.BAD_REQUEST, "Bad Request", "Required request body content is missing" );
        }

        /*
        CustomCloudFoundryClient client = getCustomCloudFoundryClient(token);

        client.deleteSpace(orgName, spaceName);
        */
        return Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) ).spaces()
            .delete( DeleteSpaceRequest.builder().spaceId( spaceGuid )
                .recursive( recursive ).async( true ).build() ).block();
    }

    /**
     * 공간 요약 정보를 조회한다.
     *
     * @param spaceId the spaceId
     * @param token the token
     * @return space summary
     * @throws Exception the exception
     */
    public GetSpaceSummaryResponse getSpaceSummary(String spaceId, String token) throws Exception{
        ReactorCloudFoundryClient cloudFoundryClient =
            Common.cloudFoundryClient(connectionContext(), tokenProvider(token));

        GetSpaceSummaryResponse respSapceSummary =
            cloudFoundryClient.spaces()
                .getSummary(GetSpaceSummaryRequest.builder()
                    .spaceId(spaceId).build()
                ).block();

        return respSapceSummary;


//
//        if(!stringNullCheck(orgName,spaceName)) {
//            throw new CloudFoundryException(HttpStatus.BAD_REQUEST, "Bad Request", "Required request body content is missing");
//        }
//
//        CustomCloudFoundryClient admin = getCustomCloudFoundryClient(adminUserName, adminPassword);
//
//        String spaceString = admin.getSpaceSummary(orgName, spaceName);
//        Space respSpace = new ObjectMapper().readValue(spaceString, Space.class);
//
//        //LOGGER.info(spaceString);
//        int memTotal = 0;
//        int memUsageTotal = 0;
//
//        for (App app : respSpace.getApps()) {
//
//            memTotal += app.getMemory() * app.getInstances();
//
//            if (app.getState().equals("STARTED")) {
//               // space.setAppCountStarted(space.getAppCountStarted() + 1);
//
//                memUsageTotal += app.getMemory() * app.getInstances();
//
//            } else if (app.getState().equals("STOPPED")) {
//                //space.setAppCountStopped(space.getAppCountStopped() + 1);
//            } else {
//                //space.setAppCountCrashed(space.getAppCountCrashed() + 1);
//            }
//        }
//
//        respSpace.setMemoryLimit(memTotal);
//        respSpace.setMemoryUsage(memUsageTotal);
//
//        return respSpace;


//        GetSpaceSummaryResponse getSpaceSummaryResponse =
//                Common.cloudFoundryClient(connectionContext(), tokenProvider(adminUserName,adminPassword))
//                        .spaces().getSummary(GetSpaceSummaryRequest.builder().spaceId(spaceId).build()).block();
//        ObjectMapper objectMapper = new ObjectMapper();
//        return objectMapper.convertValue(getSpaceSummaryResponse, Map.class);
    }

    /**
     * 공간 정보를 조회한다.
     *
     * @param spaceName the space name
     * @param orgId     the org id
     * @return the spaces info
     * @throws Exception the exception
     */
    @Deprecated
    public List<Space> getSpacesInfo(String spaceName, String orgId) throws Exception{
        Map map = new HashMap();
        map.put("spaceName" , spaceName);
        map.put("orgId" , orgId);
//        List selectSpace = spaceMapper.getSpacesInfo(map);
        List selectSpace = null;
        return selectSpace;
    }

    /**
     * 공간ID로 공간정보를 조회한다.
     *
     * @param spaceId the space id
     * @return the spaces info by id
     * @throws Exception the exception
     */
    @Deprecated
    public List<Space> getSpacesInfoById(int spaceId) throws Exception{
        Map map = new HashMap();
        map.put("spaceId" , spaceId);
//        List selectSpace = spaceMapper.getSpacesInfoById(map);
        List selectSpace = null;
        return selectSpace;
    }

    /**
     * 공간에 생성되어 있는 서비스를 조회한다.
     *
     * @param spaceId
     * @param token
     * @return
     * @throws Exception
     * @author 박철한
     * @version 2.0
     * @since 2018.4.30
     */
    public ListSpaceServicesResponse getSpaceServices(String spaceId, String token) throws Exception {
        ReactorCloudFoundryClient cloudFoundryClient =
            Common.cloudFoundryClient(connectionContext(), tokenProvider(token));

        ListSpaceServicesResponse respSpaceServices =
            cloudFoundryClient.spaces()
                .listServices(ListSpaceServicesRequest.builder()
                    .spaceId(spaceId).build()
                ).block();

        return respSpaceServices;
    }

    // TODO spaces role
    private enum SpaceRole {
        SpaceManager, SpaceDeveloper, SpaceAuditor,
        SPACEMANAGER, SPACEDEVELOPER, SPACEAUDITOR,
    }

    private List<UserSpaceRoleResource> listAllSpaceUsers( String spaceId, String token ) {
        final ListSpaceUserRolesResponse response =
            Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
                .spaces()
                .listUserRoles( ListSpaceUserRolesRequest.builder().spaceId( spaceId ).build() )
            .block();

        return response.getResources();
    }

    private List<UserResource> listSpaceManagerUsers( String spaceId, String token ) {
        final ListSpaceManagersResponse response =
            Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
                .spaces()
                .listManagers( ListSpaceManagersRequest.builder().spaceId( spaceId ).build() )
                .block();

        return response.getResources();
    }

    private List<UserResource> listSpaceDeveloperUsers( String spaceId, String token ) {
        final ListSpaceDevelopersResponse response =
            Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
                .spaces()
                .listDevelopers( ListSpaceDevelopersRequest.builder().spaceId( spaceId ).build() )
                .block();

        return response.getResources();
    }

    private List<UserResource> listSpaceAuditorUsers( String spaceId, String token ) {
        final ListSpaceAuditorsResponse response =
            Common.cloudFoundryClient( connectionContext(), tokenProvider( token ) )
                .spaces()
                .listAuditors( ListSpaceAuditorsRequest.builder().spaceId( spaceId ).build() )
                .block();

        return response.getResources();
    }

    public Map<String, Collection<UserRole>> getSpaceUserRoles( String spaceId, String token ) {
        if (null == token)
            token = adminTokenProvider.getToken( connectionContext() ).block();

        Map<String, UserRole> userRoles = new HashMap<>();
        listAllSpaceUsers( spaceId, token ).stream()
            .map( resource -> UserRole.builder().userId( resource.getMetadata().getId() )
                .userEmail( resource.getEntity().getUsername() )
                .roles( resource.getEntity().getSpaceRoles() )
                .modifiableRoles( false )
                .build()
            ).filter( ur -> null != ur )
            .forEach( ur -> userRoles.put( ur.getUserId(), ur) );

        final Map<String, Collection<UserRole>> result = new HashMap<>();
        result.put( "user_roles", userRoles.values() );
        return result;
    }

    public SpaceUsers getSpaceUserRolesBySpaceName( String orgName, String spaceName, String token ) {
        return Common.cloudFoundryOperations( connectionContext(), tokenProvider( token ) )
            .userAdmin()
            .listSpaceUsers(
                ListSpaceUsersRequest.builder().organizationName( orgName ).spaceName( spaceName ).build()
            ).block();
    }

    public boolean isSpaceManagerUsingName( String orgName, String spaceName, String token ) {
        final String spaceId = this.getSpaceUsingName( orgName, spaceName, token ).getMetadata().getId();
        final String userId = userService.getUser( token ).getUserId();
        return isSpaceManager( spaceId, userId );
    }

    public boolean isSpaceManagerUsingToken( String spaceId, String token ) {
        final String userId = userService.getUser( token ).getUserId();
        return isSpaceManager( spaceId, userId );
    }

    public boolean isSpaceManager( String spaceId, String userId ) {
        Stream<UserRole> userRoles = getSpaceUserRoles( spaceId, null ).get( "user_roles" )
            .stream().filter( ur -> ur.getRoles().contains( "SpaceManager" ) );
        boolean matches = userRoles.anyMatch( ur -> ur.getUserId().equals( userId ) );

        return matches;
    }

    private AssociateSpaceManagerResponse associateSpaceManager(String spaceId, String userId) {
        return Common.cloudFoundryClient( connectionContext(), adminTokenProvider )
            .spaces()
            .associateManager( AssociateSpaceManagerRequest.builder()
                .spaceId( spaceId ).managerId( userId ).build() )
            .block();
    }

    private AssociateSpaceDeveloperResponse associateSpaceDeveloper(String spaceId, String userId) {
        return Common.cloudFoundryClient( connectionContext(), adminTokenProvider )
            .spaces()
            .associateDeveloper( AssociateSpaceDeveloperRequest.builder()
                .spaceId( spaceId ).developerId( userId ).build() )
            .block();
    }

    private AssociateSpaceAuditorResponse associateSpaceAuditor(String spaceId, String userId) {
        return Common.cloudFoundryClient( connectionContext(), adminTokenProvider )
            .spaces()
            .associateAuditor( AssociateSpaceAuditorRequest.builder()
                .spaceId( spaceId ).auditorId( userId ).build() )
            .block();
    }

    public AbstractSpaceResource associateSpaceUserRole(
        String spaceId, String userId, String role ) {
        Objects.requireNonNull( spaceId, "Space Id" );
        Objects.requireNonNull( userId, "User Id" );
        Objects.requireNonNull( role, "role" );

        /*
        // Is needed action? Only do associate OrgManager
        if (!isSpaceManagerUsingToken( spaceId, token )) {
            final String email = userService.getUser( token ).getEmail();
            throw new CloudFoundryException( HttpStatus.FORBIDDEN,
                "This user is unauthorized to change role for this org : " + email );
        }
        */

        final SpaceRole roleEnum;
        try {
            roleEnum = SpaceRole.valueOf( role );
        } catch ( IllegalArgumentException e ) {
            LOGGER.error( "This role is invalid : {}", role );
            throw new CloudFoundryException( HttpStatus.BAD_REQUEST, "Request role is invalid : " + role );
        }

        switch( roleEnum ) {
            case SpaceManager:
            case SPACEMANAGER:
                return associateSpaceManager( spaceId, userId );
            case SpaceDeveloper:
            case SPACEDEVELOPER:
                return associateSpaceDeveloper( spaceId, userId );
            case SpaceAuditor:
            case SPACEAUDITOR:
                return associateSpaceAuditor( spaceId, userId );
            default:
                throw new CloudFoundryException( HttpStatus.BAD_REQUEST, "Request role is invalid : " + role );
        }
    }

    public List<AbstractSpaceResource> associateAllSpaceUserRolesByOrgId (
        String orgId, String userId, Iterable<String> roles) {
        final List<AbstractSpaceResource> responses = new LinkedList<>();
        final List<String> spaceIds = this.getSpaces( orgId, null ).getResources()
            .stream().map( space -> space.getMetadata().getId() ).filter( id -> null != id )
            .collect( Collectors.toList() );
        for ( String role : roles ) {
            for ( String spaceId : spaceIds ) {
                final AbstractSpaceResource response = associateSpaceUserRole( spaceId, userId, role );
                responses.add( response );
            }
        }

        return responses;
    }

    public List<AbstractSpaceResource> associateSpaceUserRolesByOrgIdAndRole ( String spaceId, String orgId ) {
        // set space role for all user in org
        final List<AbstractSpaceResource> results = new LinkedList<>();
        final Collection<UserRole> users = orgService.getOrgUserRoles( orgId, null ).get( "user_roles" );
        for (UserRole user : users) {
            final String userId = user.getUserId();
            final Set<String> roles = user.getRoles();
            List<String> spaceRoles = Collections.emptyList();
            if (roles.contains( "OrgManager" ))
                spaceRoles = SPACE_ROLES_FOR_ORGMANAGER;
            else if (roles.contains( "OrgAuditor" ))
                spaceRoles = SPACE_ROLES_FOR_ORGAUDITOR;
            else if (roles.contains( "BillingManager" ))
                spaceRoles = SPACE_ROLES_FOR_BILLINGMANAGER;

            for (String spaceRole : spaceRoles) {
                results.add( this.associateSpaceUserRole( spaceId, userId, spaceRole ) );
            }
        }

        return results;
    }

    private void removeSpaceManager( String spaceId, String userId ) {
        LOGGER.debug( "---->> Remove SpaceManager role of member({}) in space({}).", userId, spaceId );
        Common.cloudFoundryClient( connectionContext(), adminTokenProvider )
            .spaces()
            .removeManager( RemoveSpaceManagerRequest.builder()
                .spaceId( spaceId ).managerId( userId ).build() )
            .block();
    }

    private void removeSpaceDeveloper( String spaceId, String userId ) {
        LOGGER.debug( "---->> Remove SpaceDeveloper role of member({}) in space({}).", userId, spaceId );
        Common.cloudFoundryClient( connectionContext(), adminTokenProvider )
            .spaces()
            .removeDeveloper( RemoveSpaceDeveloperRequest.builder()
                .spaceId( spaceId ).developerId( userId ).build() )
            .block();
    }

    private void removeSpaceAuditor( String spaceId, String userId ) {
        LOGGER.debug( "---->> Remove SpaceAuditor role of member({}) in space({}).", userId, spaceId );
        Common.cloudFoundryClient( connectionContext(), adminTokenProvider )
            .spaces()
            .removeAuditor( RemoveSpaceAuditorRequest.builder()
                .spaceId( spaceId ).auditorId( userId ).build() )
            .block();
    }

    private void removeAllRoles ( String spaceId, String userId ) {
        LOGGER.debug( "--> Remove all member({})'s roles in space({}).", userId, spaceId );
        removeSpaceManager( spaceId, userId );
        removeSpaceDeveloper( spaceId, userId );
        removeSpaceAuditor( spaceId, userId );
        LOGGER.debug( "--> Done to remove all member({})'s roles in space({}).", userId, spaceId );
    }

    /**
     * 조직에 속한 유저에 대한 역할(Role)을 제거한다.
     *
     * @param spaceId
     * @param userId
     * @param role
     */
    public void removeSpaceUserRole ( String spaceId, String userId, String role ) {
        Objects.requireNonNull( spaceId, "Space Id" );
        Objects.requireNonNull( userId, "User Id" );
        Objects.requireNonNull( role, "role" );

        /*
        // Is needed action? Only do associate OrgManager
        if (!isSpaceManagerUsingToken(spaceId, token)) {
            final String email = userService.getUser( token ).getEmail();
            throw new CloudFoundryException( HttpStatus.FORBIDDEN,
                "This user is unauthorized to change role for this org : " + email );
        }
        */

        final SpaceRole roleEnum;
        try {
            roleEnum = SpaceRole.valueOf( role );
        } catch ( IllegalArgumentException e ) {
            LOGGER.error( "This role is invalid : {}", role );
            return;
        }

        switch ( roleEnum ) {
            case SpaceManager:
            case SPACEMANAGER:
                removeSpaceManager( spaceId, userId );
                break;
            case SpaceDeveloper:
            case SPACEDEVELOPER:
                removeSpaceDeveloper( spaceId, userId );
                break;
            case SpaceAuditor:
            case SPACEAUDITOR:
                removeSpaceAuditor( spaceId, userId );
                break;
            default:
                throw new CloudFoundryException( HttpStatus.BAD_REQUEST, "Request role is invalid : " + role );
        }
    }

    public void removeAllSpaceUserRolesByOrgId( String orgId, String userId, Iterable<String> roles ) {
        final List<String> spaceIds = this.getSpaces( orgId, null ).getResources()
            .stream().map( space -> space.getMetadata().getId() ).filter( id -> null != id )
            .collect( Collectors.toList() );
        for ( String role : roles ) {
            for ( String spaceId : spaceIds )
                removeSpaceUserRole( spaceId, userId, role );
        }
    }
}
