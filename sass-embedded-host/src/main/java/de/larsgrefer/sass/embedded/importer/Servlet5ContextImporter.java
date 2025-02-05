package de.larsgrefer.sass.embedded.importer;

import lombok.RequiredArgsConstructor;

import jakarta.servlet.ServletContext;
import java.net.URL;

/**
 * @author Lars Grefer
 */
@RequiredArgsConstructor
public class Servlet5ContextImporter extends CustomUrlImporter {

    private final ServletContext servletContext;

    @Override
    public URL canonicalizeUrl(String url) throws Exception {
        return servletContext.getResource(url);
    }

}
