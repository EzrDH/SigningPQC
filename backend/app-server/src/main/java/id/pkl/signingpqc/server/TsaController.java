package id.pkl.signingpqc.server;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import id.pkl.signingpqc.core.HttpTimestampTransport;

/** TSA lokal RFC 3161 lewat HTTP (RFC 3161 bagian 3.4): POST application/timestamp-query. */
@RestController
@RequestMapping("/api/tsa")
public class TsaController {

    private final TsaService tsa;

    public TsaController(TsaService tsa) {
        this.tsa = tsa;
    }

    @PostMapping(value = "/{family}", consumes = HttpTimestampTransport.REQUEST_TYPE,
            produces = HttpTimestampTransport.RESPONSE_TYPE)
    public ResponseEntity<byte[]> timestamp(@PathVariable String family, @RequestBody byte[] request)
            throws GeneralSecurityException, IOException {
        return ResponseEntity.ok(tsa.local(TsaService.family(family)).handle(request));
    }
}
