package org.opencds.cqf.fhir.cr.cpg.r4;

import static org.opencds.cqf.fhir.utility.r4.Parameters.parameters;
import static org.opencds.cqf.fhir.utility.r4.Parameters.part;

import ca.uhn.fhir.repository.IRepository;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.opencds.cqf.fhir.cql.Engines;
import org.opencds.cqf.fhir.cql.EvaluationSettings;
import org.opencds.cqf.fhir.cql.LibraryEngine;
import org.opencds.cqf.fhir.cr.cpg.CqlExecutionProcessor;
import org.opencds.cqf.fhir.utility.repository.Repositories;

public class R4CqlExecutionService {

    protected IRepository repository;
    protected EvaluationSettings evaluationSettings;

    public R4CqlExecutionService(IRepository repository, EvaluationSettings evaluationSettings) {
        this.repository = repository;
        this.evaluationSettings = evaluationSettings;
    }


    // should use adapters to make this version agnostic
    public Parameters evaluate(
            // RequestDetails requestDetails,
            String subject,
            String expression,
            Parameters parameters,
            List<Parameters> library,
            BooleanType useServerData,
            Bundle data,
            List<Parameters> prefetchData,
            Endpoint dataEndpoint,
            Endpoint contentEndpoint,
            Endpoint terminologyEndpoint,
            String content) {


        var baseCqlExecutionProcessor = new CqlExecutionProcessor();

        if (prefetchData != null) {
            return parameters(part("invalid parameters", (OperationOutcome)
                    baseCqlExecutionProcessor.createIssue("warning", "prefetchData is not yet supported", repository)));
        }

        if (expression == null && content == null) {
            return parameters(part("invalid parameters", (OperationOutcome) baseCqlExecutionProcessor.createIssue(
                    "error",
                    "The $cql operation requires the expression parameter and/or content parameter to exist",
                    repository)));
        }

        try {
            if (contentEndpoint != null) {
                repository = Repositories.proxy(
                        repository, useServerData != null ? useServerData.booleanValue() : true, dataEndpoint, contentEndpoint, terminologyEndpoint);
            }
            var libraryEngine = new LibraryEngine(repository, this.evaluationSettings);

            var libraries = baseCqlExecutionProcessor.resolveIncludedLibraries(library);

            if (StringUtils.isBlank(content)) {

                return (Parameters) libraryEngine.evaluateExpression(
                        expression,
                        parameters == null ? new Parameters() : parameters,
                        null,
                        subject,
                        libraries,
                        data,
                        null,
                        null);
            }

            // Use CLI approach: extract library name and create simple identifier
            String libraryName = null;
            if (!StringUtils.isBlank(content)) {
                // Extract library name from content using regex
                java.util.regex.Pattern libraryPattern = java.util.regex.Pattern.compile("library\\s+([A-Za-z0-9_]+)");
                java.util.regex.Matcher libraryMatcher = libraryPattern.matcher(content);
                if (libraryMatcher.find()) {
                    libraryName = libraryMatcher.group(1);
                } else {
                    return parameters(part("evaluation error", (OperationOutcome)
                            baseCqlExecutionProcessor.createIssue("error", "Could not extract library name from CQL content", repository)));
                }
            }
            
            

            // If we don't have a library name from content, we need to handle this case
            if (libraryName == null) {
                return parameters(part("evaluation error", (OperationOutcome)
                        baseCqlExecutionProcessor.createIssue("error", "Could not extract library name from content", repository)));
            }

            // Create simple identifier like CLI does (no version)
            var libraryIdentifier = new org.hl7.elm.r1.VersionedIdentifier().withId(libraryName);
            
            // Prepare context parameter like CLI does
            var contextParameter = subject != null ? 
                org.apache.commons.lang3.tuple.Pair.<String, Object>of("Patient", subject.startsWith("Patient/") ? subject.replace("Patient/", "") : subject) : 
                null;
            
            // Create engine first, then register source provider like CLI does
            var engine = Engines.forRepository(repository, evaluationSettings, data);
            
            // Register inline CQL content as source provider (CLI approach)
            var inlineSourceProvider = new org.cqframework.cql.cql2elm.StringLibrarySourceProvider(java.util.Arrays.asList(content));
            engine.getEnvironment()
                    .getLibraryManager()
                    .getLibrarySourceLoader()
                    .registerProvider(inlineSourceProvider);
            
            // Use CLI approach: null expressions means "evaluate all"
            var result = engine.evaluate(libraryIdentifier, null, contextParameter);
            
            // Convert result to FHIR Parameters
            var cqlFhirParametersConverter = Engines.getCqlFhirParametersConverter(repository.fhirContext());
            return (Parameters) cqlFhirParametersConverter.toFhirParameters(result);

        } catch (Exception e) {
            return parameters(part("evaluation error", (OperationOutcome)
                    baseCqlExecutionProcessor.createIssue("error", e.getMessage(), repository)));
        }
    }
}
