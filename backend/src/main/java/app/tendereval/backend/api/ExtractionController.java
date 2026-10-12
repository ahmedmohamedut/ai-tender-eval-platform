package app.tendereval.backend.api;

import org.springframework.web.bind.annotation.RestController;

/**
 * Every operation of the Extraction group is answered with 501 problem+json until it is built: the
 * generated interface's default methods throw. Implemented operations are overridden here.
 */
@RestController
public class ExtractionController implements ExtractionApi {}
