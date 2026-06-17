package ca.uhn.fhir.jpa.starter.common.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.interceptor.validation.IRepositoryValidatingRule;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingRuleBuilder;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ca.uhn.fhir.jpa.starter.common.validation.IRepositoryValidationInterceptorFactory.ENABLE_REPOSITORY_VALIDATING_INTERCEPTOR;

/**
 * This class can be customized to enable the {@link ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor}
 * on this server.
 * <p>
 * The <code>enable_repository_validating_interceptor</code> property must be enabled in <code>application.yaml</code>
 * in order to use this class.
 */
@ConditionalOnProperty(prefix = "hapi.fhir", name = ENABLE_REPOSITORY_VALIDATING_INTERCEPTOR, havingValue = "true")
@Configuration
@Conditional(OnR4Condition.class)
public class RepositoryValidationInterceptorFactoryR4 implements IRepositoryValidationInterceptorFactory {

	private final FhirContext fhirContext;
	private final RepositoryValidatingRuleBuilder repositoryValidatingRuleBuilder;
	private final IFhirResourceDao structureDefinitionResourceProvider;

	public RepositoryValidationInterceptorFactoryR4(
			RepositoryValidatingRuleBuilder repositoryValidatingRuleBuilder, DaoRegistry daoRegistry) {
		this.repositoryValidatingRuleBuilder = repositoryValidatingRuleBuilder;
		this.fhirContext = daoRegistry.getSystemDao().getContext();
		structureDefinitionResourceProvider = daoRegistry.getResourceDao("StructureDefinition");
	}

	private static final Logger log = LoggerFactory.getLogger(RepositoryValidationInterceptorFactoryR4.class);

	private static final String[][] SLICING_EXTENSIONS = {
		{"http://hl7.org/fhir/StructureDefinition/individual-genderIdentity",
		 "Individual Gender Identity"},
		{"http://hl7.org/fhir/StructureDefinition/individual-recordedSexOrGender",
		 "Individual Recorded Sex Or Gender"},
		{"http://hl7.org/fhir/StructureDefinition/individual-pronouns",
		 "Individual Pronouns"}
	};

	private void ensureSlicingExtensions() {
		for (String[] ext : SLICING_EXTENSIONS) {
			String url = ext[0];
			String title = ext[1];
			IBundleProvider result = structureDefinitionResourceProvider.search(
				new SearchParameterMap()
					.setLoadSynchronous(true)
					.add("url", new UriParam(url)));
			if (result.size() > 0) {
				log.debug("Extension SD already stored: {}", url);
				continue;
			}
			StructureDefinition sd = new StructureDefinition();
			sd.setId("phcore-ext-" + title.replace(" ", ""));
			sd.setUrl(url);
			sd.setName(title.replace(" ", ""));
			sd.setTitle(title);
			sd.setStatus(Enumerations.PublicationStatus.ACTIVE);
			sd.setKind(StructureDefinition.StructureDefinitionKind.COMPLEXTYPE);
			sd.setAbstract(false);
			sd.setType("Extension");
			sd.setBaseDefinition("http://hl7.org/fhir/StructureDefinition/Extension");
			sd.setDerivation(StructureDefinition.TypeDerivationRule.CONSTRAINT);
			sd.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
			try {
				structureDefinitionResourceProvider.create(sd, new SystemRequestDetails());
				log.info("Inserted extension SD for slicing: {}", url);
			} catch (Exception e) {
				log.warn("Failed to insert extension SD {}: {}", url, e.getMessage());
			}
		}
	}

	@Override
	public RepositoryValidatingInterceptor buildUsingStoredStructureDefinitions() {

		ensureSlicingExtensions();

		IBundleProvider results = structureDefinitionResourceProvider.search(new SearchParameterMap()
				.setLoadSynchronous(true)
				.add(StructureDefinition.SP_KIND, new TokenParam("resource")));
		Map<String, List<StructureDefinition>> structureDefinitions = results.getResources(0, results.size()).stream()
				.map(StructureDefinition.class::cast)
				.collect(Collectors.groupingBy(StructureDefinition::getType));

		structureDefinitions.forEach((key, value) -> {
			String[] urls = value.stream().map(StructureDefinition::getUrl).toArray(String[]::new);
			repositoryValidatingRuleBuilder
					.forResourcesOfType(key)
					.requireAtLeastOneProfileOf(urls)
					.and()
					.requireValidationToDeclaredProfiles()
					.rejectOnSeverity(ResultSeverityEnum.WARNING)
					.suppressNoBindingMessage()
					.suppressWarningForExtensibleValueSetValidation();
		});

		List<IRepositoryValidatingRule> rules = repositoryValidatingRuleBuilder.build();
		return new RepositoryValidatingInterceptor(fhirContext, rules);
	}

	@Override
	public RepositoryValidatingInterceptor build() {

		// Customize the ruleBuilder here to have the rules you want! We will give a simple example
		// of enabling validation for all Patient resources
		repositoryValidatingRuleBuilder
				.forResourcesOfType("Patient")
				.requireAtLeastProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
				.and()
				.requireValidationToDeclaredProfiles();

		// Do not customize below this line
		List<IRepositoryValidatingRule> rules = repositoryValidatingRuleBuilder.build();
		return new RepositoryValidatingInterceptor(fhirContext, rules);
	}
}
