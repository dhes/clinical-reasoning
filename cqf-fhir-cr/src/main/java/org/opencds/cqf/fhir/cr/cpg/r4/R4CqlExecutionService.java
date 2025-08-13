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

    // Helper method from CQF Ruler for resolving context parameters
    private org.apache.commons.lang3.tuple.Pair<String, Object> resolveContextParameter(String subject) {
        if (StringUtils.isBlank(subject)) {
            return null;
        }
        String[] reference = subject.split("/");
        return org.apache.commons.lang3.tuple.Pair.of(
            reference.length > 1 ? reference[0] : "Patient", 
            reference.length > 1 ? reference[1] : subject
        );
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
            // Default useServerData to true if null (per FHIR spec)
            if (useServerData == null) {
                useServerData = new BooleanType(true);
            }
            
            // Temporarily bypass repository proxy to test patient context resolution
            // if (contentEndpoint != null) {
            //     repository = Repositories.proxy(
            //             repository, useServerData != null ? useServerData.booleanValue() : true, dataEndpoint, contentEndpoint, terminologyEndpoint);
            // }
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

            // Extract library name and version from content using regex
            String libraryName = null;
            String libraryVersion = null;
            if (!StringUtils.isBlank(content)) {
                // Extract library name and optional version from content using regex
                // Pattern matches: library LibraryName version 'x.x.x' or library LibraryName version "x.x.x" or just library LibraryName
                java.util.regex.Pattern libraryPattern = java.util.regex.Pattern.compile("library\\s+([A-Za-z0-9_]+)(?:\\s+version\\s+['\"]([^'\"]+)['\"])?");
                java.util.regex.Matcher libraryMatcher = libraryPattern.matcher(content);
                if (libraryMatcher.find()) {
                    libraryName = libraryMatcher.group(1);
                    libraryVersion = libraryMatcher.group(2); // Will be null if no version specified
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

            // Create versioned identifier with both name and version (if available)
            var libraryIdentifier = new org.hl7.elm.r1.VersionedIdentifier().withId(libraryName);
            if (libraryVersion != null) {
                libraryIdentifier.withVersion(libraryVersion);
            }
            
            // Use CQF Ruler approach: resolve context parameter like the original
            var contextParameter = resolveContextParameter(subject);
            System.out.println("DEBUG: contextParameter = " + contextParameter);
            
            // Remove from cache like CQF Ruler does (ensures content changes are reflected)
            evaluationSettings.getLibraryCache().remove(libraryIdentifier);
            
            // Create engine with inline source provider (simplified approach)
            var engine = Engines.forRepository(repository, evaluationSettings, data);
            
            // Register inline CQL content as source provider
            var inlineSourceProvider = new org.cqframework.cql.cql2elm.StringLibrarySourceProvider(java.util.Arrays.asList(content));
            engine.getEnvironment()
                    .getLibraryManager()
                    .getLibrarySourceLoader()
                    .registerProvider(inlineSourceProvider);
            
            System.out.println("DEBUG: About to evaluate with libraryIdentifier = " + libraryIdentifier.getId() + " version = " + libraryIdentifier.getVersion());
            
            // Use engine evaluation with proper context parameter
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
