package com.cpgame.curupira;

import com.cpgame.curupira.codec.SignaptCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignaptCodecTest {
    @Test
    void matchesCapturedInitialDataSignatureOracle() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("currency", "undefined");
        fields.put("gid", "2350");
        fields.put("language", "pt-br");
        fields.put("ai", "luck_single_10229");
        String actual = new SignaptCodec().calculate(fields, "1787844617722");
        assertThat(actual).isEqualTo("f52213b3d95f109078ff14cc39599f17");
    }
}
