package ph.ereferral.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@Interceptor
public class PhEreferralDeduplicationInterceptor {

	private static final Logger ourLog = LoggerFactory.getLogger(PhEreferralDeduplicationInterceptor.class);

	private final DaoRegistry daoRegistry;

	public PhEreferralDeduplicationInterceptor(DaoRegistry daoRegistry) {
		this.daoRegistry = daoRegistry;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void deduplicateOnCreate(
			ServletRequestDetails theRequestDetails,
			RestOperationTypeEnum theOperationType) {
		if (theOperationType != RestOperationTypeEnum.CREATE) return;
		if (theRequestDetails.getResource() == null) return;

		IBaseResource resource = theRequestDetails.getResource();
		IdType existingId = findExistingByMatch(resource);
		if (existingId == null) return;

		ourLog.info("DEDUP: Found match → {} — merging", existingId.getIdPart());
		IBaseResource merged = mergeIntoExisting(resource, existingId);
		if (merged == null) return;

		ourLog.info("DEDUP: Merge complete into {}", existingId.getIdPart());

		String summary = buildMergeSummary(resource, existingId);
		throw new DedupResponseException(existingId, summary);
	}

	private String buildMergeSummary(IBaseResource incoming, IdType existingId) {
		StringBuilder sb = new StringBuilder();
		sb.append("Resource deduplicated and merged into ").append(existingId.toUnqualifiedVersionless().getValue());
		if (incoming instanceof Patient p) {
			if (p.hasGender()) sb.append(" [gender=").append(p.getGender().toCode()).append("]");
			if (!p.getName().isEmpty()) {
				var n = p.getNameFirstRep();
				sb.append(" [name=").append(n.getFamily()).append(", ").append(String.join(" ", n.getGivenAsSingleString())).append("]");
			}
		}
		sb.append(" — incoming fields overwrote existing where present; "
				+ "existing non-empty fields preserved; identifier lists unioned.");
		return sb.toString();
	}

	private IdType findExistingByMatch(IBaseResource resource) {
		if (resource instanceof Patient p) {
			for (Identifier id : p.getIdentifier()) {
				if (!id.hasSystem() || !id.hasValue()) continue;
				IBaseResource existing = searchByIdentifier("Patient", id.getSystem(), id.getValue());
				if (existing != null) return new IdType(existing.getIdElement().getValue());
			}
		} else if (resource instanceof Practitioner p) {
			for (Identifier id : p.getIdentifier()) {
				if (!id.hasSystem() || !id.hasValue()) continue;
				IBaseResource existing = searchByIdentifier("Practitioner", id.getSystem(), id.getValue());
				if (existing != null) return new IdType(existing.getIdElement().getValue());
			}
		} else if (resource instanceof Organization o) {
			for (Identifier id : o.getIdentifier()) {
				if (!id.hasSystem() || !id.hasValue()) continue;
				IBaseResource existing = searchByIdentifier("Organization", id.getSystem(), id.getValue());
				if (existing != null) return new IdType(existing.getIdElement().getValue());
			}
		}
		return null;
	}

	private IBaseResource searchByIdentifier(String resourceType, String system, String value) {
		SearchParameterMap params = new SearchParameterMap();
		params.setLoadSynchronous(true);
		params.add("identifier", new TokenParam(system, value));
		params.setCount(50);
		IBundleProvider result = daoRegistry.getResourceDao(resourceType).search(params, new SystemRequestDetails());
		List<IBaseResource> resources = result.getResources(0, Integer.MAX_VALUE);
		if (resources.isEmpty()) return null;
		return resources.stream()
				.max(Comparator.comparing(r -> r.getMeta().getLastUpdated()))
				.orElse(null);
	}

	private IBaseResource mergeIntoExisting(IBaseResource incoming, IdType existingId) {
		try {
			IBaseResource existing = daoRegistry.getResourceDao(existingId.getResourceType()).read(existingId);
			IBaseResource merged;
			if (existing instanceof Patient ep && incoming instanceof Patient ip) {
				merged = mergePatient(ep, ip);
			} else if (existing instanceof Practitioner ep && incoming instanceof Practitioner ip) {
				merged = mergePractitioner(ep, ip);
			} else if (existing instanceof Organization eo && incoming instanceof Organization io) {
				merged = mergeOrganization(eo, io);
			} else {
				return null;
			}
			merged.setId(existingId.toUnqualifiedVersionless());
			daoRegistry.getResourceDao(existingId.getResourceType()).update(merged, new SystemRequestDetails());
			return merged;
		} catch (Exception e) {
			ourLog.error("DEDUP: Merge failed", e);
			return null;
		}
	}

	private Patient mergePatient(Patient existing, Patient incoming) {
		Patient result = incoming.copy();
		mergeIdentifiers(existing.getIdentifier(), result.getIdentifier());
		if (isEmpty(result.getName()) && !isEmpty(existing.getName())) result.setName(existing.getName());
		if (!result.hasGender() && existing.hasGender()) result.setGender(existing.getGender());
		if (!result.hasBirthDate() && existing.hasBirthDate()) result.setBirthDateElement(existing.getBirthDateElement());
		if (isEmpty(result.getTelecom()) && !isEmpty(existing.getTelecom())) result.setTelecom(existing.getTelecom());
		if (isEmpty(result.getAddress()) && !isEmpty(existing.getAddress())) result.setAddress(existing.getAddress());
		if (isEmpty(result.getContact()) && !isEmpty(existing.getContact())) result.setContact(existing.getContact());
		result.setActive(existing.getActive());
		return result;
	}

	private Practitioner mergePractitioner(Practitioner existing, Practitioner incoming) {
		Practitioner result = incoming.copy();
		mergeIdentifiers(existing.getIdentifier(), result.getIdentifier());
		if (isEmpty(result.getName()) && !isEmpty(existing.getName())) result.setName(existing.getName());
		if (isEmpty(result.getTelecom()) && !isEmpty(existing.getTelecom())) result.setTelecom(existing.getTelecom());
		if (isEmpty(result.getAddress()) && !isEmpty(existing.getAddress())) result.setAddress(existing.getAddress());
		if (!result.hasGender() && existing.hasGender()) result.setGender(existing.getGender());
		if (!result.hasBirthDate() && existing.hasBirthDate()) result.setBirthDateElement(existing.getBirthDateElement());
		result.setActive(existing.getActive());
		return result;
	}

	private Organization mergeOrganization(Organization existing, Organization incoming) {
		Organization result = incoming.copy();
		mergeIdentifiers(existing.getIdentifier(), result.getIdentifier());
		if (!result.hasName() && existing.hasName()) result.setName(existing.getName());
		if (isEmpty(result.getTelecom()) && !isEmpty(existing.getTelecom())) result.setTelecom(existing.getTelecom());
		if (isEmpty(result.getAddress()) && !isEmpty(existing.getAddress())) result.setAddress(existing.getAddress());
		return result;
	}

	private void mergeIdentifiers(List<Identifier> existing, List<Identifier> result) {
		for (Identifier eid : existing) {
			boolean exists = result.stream().anyMatch(rid ->
					eid.hasSystem() && rid.hasSystem() && eid.getSystem().equals(rid.getSystem()) &&
					eid.hasValue() && rid.hasValue() && eid.getValue().equals(rid.getValue()));
			if (!exists) result.add(eid);
		}
	}

	private boolean isEmpty(List<?> list) {
		return list == null || list.isEmpty();
	}

	private static class DedupResponseException extends BaseServerResponseException {
		private static final long serialVersionUID = 1L;

		DedupResponseException(IdType existingId, String summary) {
			super(200, summary);
		}

		@Override
		public int getStatusCode() {
			return 200;
		}
	}
}
