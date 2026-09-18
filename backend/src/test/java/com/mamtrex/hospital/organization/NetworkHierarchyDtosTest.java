package com.mamtrex.hospital.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 T031 (US1): strict serialization shape of the network-hierarchy
 * response records. The hierarchy contract is an explicit, immutable,
 * deterministic allowlist — exactly the three planning-contract levels
 * (NetworkHierarchy / HospitalView / BranchView), never a JPA entity, never
 * persistence metadata (createdAt/updatedAt/version), never a lazy graph.
 * Every property is always serialized (no conditional omission), the key
 * order is the record declaration order (deterministic bytes), the lists
 * are defensively copied, and two serializations of equal values are
 * byte-identical.
 */
class NetworkHierarchyDtosTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HOSPITAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BRANCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /** Exact planning-contract allowlist of the top-level hierarchy body. */
    private static final List<String> NETWORK_KEYS = List.of(
            "organizationId", "organizationCode", "organizationName", "hospitals");

    /** Exact planning-contract allowlist of one hospital view. */
    private static final List<String> HOSPITAL_KEYS = List.of(
            "id", "code", "name", "regionLabel", "timeZone", "active", "branches");

    /** Exact planning-contract allowlist of one branch view. */
    private static final List<String> BRANCH_KEYS = List.of(
            "id", "hospitalId", "code", "name", "timeZone", "active");

    private NetworkHierarchyDtos.BranchView branchView() {
        return new NetworkHierarchyDtos.BranchView(
                BRANCH_ID, HOSPITAL_ID, "DEMO-BR-001", "Demo Main Branch", ZoneId.of("UTC"), true);
    }

    private NetworkHierarchyDtos.HospitalView hospitalView(NetworkHierarchyDtos.BranchView branch) {
        return new NetworkHierarchyDtos.HospitalView(
                HOSPITAL_ID, "DEMO-HOSP-001", "Demo Legacy Hospital", "Demo Region",
                ZoneId.of("UTC"), true, List.of(branch));
    }

    private NetworkHierarchyDtos.NetworkHierarchy hierarchy(NetworkHierarchyDtos.HospitalView hospital) {
        return new NetworkHierarchyDtos.NetworkHierarchy(
                ORG_ID, "DEMO-ORG-001", "Demo Synthetic Hospital", List.of(hospital));
    }

    @Test
    void hierarchyBodyCarriesExactlyTheContractKeysInDeclarationOrder() throws Exception {
        String json = mapper.writeValueAsString(hierarchy(hospitalView(branchView())));
        Map<String, Object> body = mapper.readValue(json, LinkedHashMap.class);
        assertEquals(NETWORK_KEYS, List.copyOf(body.keySet()),
                "the hierarchy body must expose exactly the contract keys in declaration order");
    }

    @Test
    void hospitalViewCarriesExactlyTheContractKeysInDeclarationOrder() throws Exception {
        String json = mapper.writeValueAsString(hospitalView(branchView()));
        Map<String, Object> hospital = mapper.readValue(json, LinkedHashMap.class);
        assertEquals(HOSPITAL_KEYS, List.copyOf(hospital.keySet()),
                "the hospital view must expose exactly the contract keys in declaration order");
    }

    @Test
    void branchViewCarriesExactlyTheContractKeysInDeclarationOrder() throws Exception {
        String json = mapper.writeValueAsString(branchView());
        Map<String, Object> branch = mapper.readValue(json, LinkedHashMap.class);
        assertEquals(BRANCH_KEYS, List.copyOf(branch.keySet()),
                "the branch view must expose exactly the contract keys in declaration order");
    }

    @Test
    void ianaZonesSerializeAsStableRegionIdStrings() throws Exception {
        String json = mapper.writeValueAsString(branchView());
        @SuppressWarnings("unchecked")
        Map<String, Object> branch = mapper.readValue(json, Map.class);
        assertEquals("UTC", branch.get("timeZone"), "a zone must render as its IANA region id string");
        String newYork = mapper.writeValueAsString(new NetworkHierarchyDtos.BranchView(
                BRANCH_ID, HOSPITAL_ID, "DEMO-BR-002", "Demo North Branch",
                ZoneId.of("America/New_York"), true));
        @SuppressWarnings("unchecked")
        Map<String, Object> north = mapper.readValue(newYork, Map.class);
        assertEquals("America/New_York", north.get("timeZone"),
                "a non-UTC zone must render as its exact IANA region id");
    }

    @Test
    void everyPropertyIsAlwaysSerializedIncludingFalseAndEmptyCollections() throws Exception {
        String json = mapper.writeValueAsString(new NetworkHierarchyDtos.NetworkHierarchy(
                ORG_ID, "DEMO-ORG-001", "Demo Synthetic Hospital", List.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(json, Map.class);
        assertTrue(body.containsKey("hospitals"), "an empty hospital list must still be serialized");
        assertEquals(List.of(), body.get("hospitals"));

        String inactive = mapper.writeValueAsString(new NetworkHierarchyDtos.BranchView(
                BRANCH_ID, HOSPITAL_ID, "DEMO-BR-001", "Demo Main Branch", ZoneId.of("UTC"), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> inactiveBranch = mapper.readValue(inactive, Map.class);
        assertEquals(false, inactiveBranch.get("active"),
                "an explicitly false flag must still be serialized, never omitted");
    }

    @Test
    void listPropertiesAreDefensivelyCopiedAndImmutable() {
        List<NetworkHierarchyDtos.BranchView> mutableBranches = new ArrayList<>();
        mutableBranches.add(branchView());
        NetworkHierarchyDtos.HospitalView hospital = new NetworkHierarchyDtos.HospitalView(
                HOSPITAL_ID, "DEMO-HOSP-001", "Demo Legacy Hospital", "Demo Region",
                ZoneId.of("UTC"), true, mutableBranches);

        assertThrows(UnsupportedOperationException.class, () -> hospital.branches().add(branchView()),
                "the hospital branch list must be immutable");

        mutableBranches.clear();
        assertEquals(1, hospital.branches().size(),
                "mutating the caller's source list must never change an already built view");

        List<NetworkHierarchyDtos.HospitalView> mutableHospitals = new ArrayList<>();
        mutableHospitals.add(hospital);
        NetworkHierarchyDtos.NetworkHierarchy network = new NetworkHierarchyDtos.NetworkHierarchy(
                ORG_ID, "DEMO-ORG-001", "Demo Synthetic Hospital", mutableHospitals);
        assertThrows(UnsupportedOperationException.class, () -> network.hospitals().add(hospital),
                "the hierarchy hospital list must be immutable");
        mutableHospitals.clear();
        assertEquals(1, network.hospitals().size(),
                "mutating the caller's source list must never change an already built hierarchy");
    }

    @Test
    void equalValuesSerializeByteIdenticallyForDeterministicResponses() throws Exception {
        String first = mapper.writeValueAsString(hierarchy(hospitalView(branchView())));
        String second = mapper.writeValueAsString(hierarchy(hospitalView(branchView())));
        assertEquals(first, second, "equal hierarchy values must serialize to identical JSON");
    }

    @Test
    void viewsNeverExposePersistenceMetadata() throws Exception {
        String json = mapper.writeValueAsString(hierarchy(hospitalView(branchView())));
        for (String forbidden : List.of("createdAt", "updatedAt", "version", "new", "entityManager")) {
            assertFalse(json.contains(forbidden),
                    "persistence metadata key '" + forbidden + "' must never appear in a view");
        }
        // Branch views carry the owning hospital as an explicit id — never a nested entity graph.
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hospitals = (List<Map<String, Object>>) body.get("hospitals");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>) hospitals.get(0).get("branches");
        assertEquals(HOSPITAL_ID.toString(), branches.get(0).get("hospitalId"),
                "the branch view carries its owning hospital as an explicit UUID field");
        assertTrue(branches.get(0).keySet().stream().noneMatch(key -> key.toLowerCase().contains("hospital")
                        && !key.equals("hospitalId")),
                "no hospital object graph may leak through a branch view");
    }
}
