package net.exoego.keycloak.fuhito.keycloak;

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

    private static final List<ProviderConfigProperty> CONFIG = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(CONFIG);
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(CONFIG, AssignmentsProtocolMapper.class);
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
        token.getOtherClaims().put(claim == null || claim.isBlank() ? DEFAULT_CLAIM : claim,
                assignments(session, userSession));
    }

    private static List<Map<String, Object>> assignments(KeycloakSession session, UserSessionModel userSession) {
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        String realmId = userSession.getRealm().getId();
        MembershipService membership = new MembershipService(new JpaAssignmentStore(em, realmId),
                new JpaEmploymentStore(em, realmId), new JpaOrgUnitStore(em, realmId),
                new JpaRefItemStore(em, realmId), userId -> true, RealmTime.clockOrUtc(userSession.getRealm()));
        return membership.assignmentsOf(userSession.getUser().getId(), null, true).stream()
                .map(AssignmentsProtocolMapper::entry)
                .toList();
    }

    private static Map<String, Object> entry(Member member) {
        Map<String, Object> entry = new LinkedHashMap<>();
        var row = member.assignment();
        entry.put("assignmentId", row.assignmentId());
        entry.put("orgUnitId", row.orgUnitId());
        entry.put("orgUnitName", member.orgUnit().version().name());
        entry.put("orgUnitPath", member.orgUnitPath().stream()
                .map(unit -> Map.of("id", unit.unit().id(), "name", unit.version().name()))
                .toList());
        putIfPresent(entry, "orgUnitCode", member.orgUnit().unit().code());
        putIfPresent(entry, "jobTitle", member.jobTitle());
        putIfPresent(entry, "occupation", member.occupation());
        putIfPresent(entry, "kind", member.kind());
        entry.put("head", row.head());
        entry.put("primary", row.primary());
        putIfPresent(entry, "staffNumber", member.employment().staffNumber());
        entry.put("status", member.terms().status().name());
        return entry;
    }

    private static void putIfPresent(Map<String, Object> entry, String key, Object value) {
        if (value != null) {
            entry.put(key, value);
        }
    }
}
