package net.exoego.keycloak.fuhito.keycloak;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.service.Member;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.UnitDays;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/**
 * Adds the assignments of the user that hold today to tokens.
 * An application can then read where the user works with no extra call.
 */
public class AssignmentsProtocolMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    /**
     * The provider ID.
     */
    public static final String ID = "fuhito-assignments-mapper";

    /**
     * The claim name when the mapper has none.
     */
    public static final String DEFAULT_CLAIM = "assignments";

    /**
     * The setting that adds the staff number to each entry.
     */
    public static final String INCLUDE_STAFF_NUMBER = "fuhito.include.staffNumber";

    /**
     * The setting that adds the employment status to each entry.
     */
    public static final String INCLUDE_STATUS = "fuhito.include.status";

    private static final List<ProviderConfigProperty> CONFIG = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(CONFIG);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(CONFIG, AssignmentsProtocolMapper.class);
        CONFIG.add(new ProviderConfigProperty(INCLUDE_STAFF_NUMBER, "Add staff number",
                "Adds the staff number of the employment to each entry. Every client that gets the token can"
                        + " read it.", ProviderConfigProperty.BOOLEAN_TYPE, "false"));
        CONFIG.add(new ProviderConfigProperty(INCLUDE_STATUS, "Add employment status",
                "Adds the employment status to each entry, for example that the user is on leave. Every client"
                        + " that gets the token can read it.", ProviderConfigProperty.BOOLEAN_TYPE, "false"));
    }

    /**
     * Creates the mapper.
     */
    public AssignmentsProtocolMapper() {
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "Org assignments";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Adds the org unit assignments of the user that hold today, with job title, occupation, and the"
                + " path of each org unit.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession session, ClientSessionContext clientSessionCtx) {
        String claim = mappingModel.getConfig().get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME);
        boolean staffNumber = Boolean.parseBoolean(mappingModel.getConfig().get(INCLUDE_STAFF_NUMBER));
        boolean status = Boolean.parseBoolean(mappingModel.getConfig().get(INCLUDE_STATUS));
        token.getOtherClaims().put(claim == null || claim.isBlank() ? DEFAULT_CLAIM : claim,
                assignments(session, userSession, staffNumber, status));
    }

    private static List<Map<String, Object>> assignments(KeycloakSession session, UserSessionModel userSession,
            boolean staffNumber, boolean status) {
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        String realmId = userSession.getRealm().getId();
        var orgUnits = new JpaOrgUnitStore(em, realmId);
        MembershipService membership = new MembershipService(new JpaAssignmentStore(em, realmId),
                new JpaEmploymentStore(em, realmId), orgUnits, new JpaRefItemStore(em, realmId), userId -> true,
                new UnitDays(orgUnits, RealmTime.ruleOrUtc(userSession.getRealm()), Clock.systemUTC()));
        return membership.assignmentsOf(userSession.getUser().getId(), null, true).stream()
                .map(member -> entry(member, staffNumber, status))
                .toList();
    }

    private static Map<String, Object> entry(Member member, boolean staffNumber, boolean status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        var row = member.assignment();
        entry.put("assignmentId", row.assignmentId());
        entry.put("orgUnitId", row.orgUnitId());
        entry.put("orgUnitName", member.orgUnit().version().name());
        entry.put("orgUnitPath", member.orgUnitPath().stream()
                .map(unit -> Map.of("id", unit.unit().id(), "name", unit.version().name()))
                .toList());
        putIfPresent(entry, "orgUnitCode", member.orgUnit().version().code());
        putIfPresent(entry, "jobTitle", member.jobTitle());
        putIfPresent(entry, "occupation", member.occupation());
        putIfPresent(entry, "kind", member.kind());
        entry.put("head", row.head());
        entry.put("primary", row.primary());
        if (staffNumber) {
            putIfPresent(entry, "staffNumber", member.employment().staffNumber());
        }
        if (status) {
            entry.put("status", member.terms().status().name());
        }
        return entry;
    }

    private static void putIfPresent(Map<String, Object> entry, String key, Object value) {
        if (value != null) {
            entry.put(key, value);
        }
    }
}
