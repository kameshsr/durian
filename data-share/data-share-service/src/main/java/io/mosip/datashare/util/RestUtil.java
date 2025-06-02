package io.mosip.datashare.util;

import com.google.gson.Gson;
import io.mosip.datashare.constant.ApiName;
import io.mosip.datashare.dto.*;
import io.mosip.datashare.exception.ApiNotAccessibleException;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.StringUtils;
import io.mosip.kernel.core.util.TokenHandlerUtil;
import jakarta.annotation.PostConstruct;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

@Component
public class RestUtil {

    @Value("${mosip.data.share.restTemplate.max-connection-per-route:20}")
    private int maxConnectionPerRoute;

    @Value("${mosip.data.share.restTemplate.total-max-connections:100}")
    private int totalMaxConnection;

    @Autowired
    private Environment environment;

    private static final String AUTHORIZATION = "Authorization=";
    private RestTemplate localRestTemplate;

    @PostConstruct
    private void loadRestTemplate() throws Exception {
        localRestTemplate = getRestTemplate();
    }

    public RestTemplate getRestTemplate() throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
        if (localRestTemplate == null) {
            TrustStrategy acceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;

            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, acceptingTrustStrategy)
                    .build();

            SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(sslContext);

            HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnPerRoute(maxConnectionPerRoute)
                    .setMaxConnTotal(totalMaxConnection)
                    .setSSLSocketFactory(socketFactory)
                    .build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();

            HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
            localRestTemplate = new RestTemplate(requestFactory);
        }

        return localRestTemplate;
    }


    public <T> T postApi(ApiName apiName, List<String> pathsegments, String queryParamName, String queryParamValue,
                         MediaType mediaType, Object requestType, Class<?> responseClass) throws ApiNotAccessibleException {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(Objects.requireNonNull(environment.getProperty(apiName.name())));

            if (pathsegments != null) {
                pathsegments.stream().filter(Objects::nonNull).filter(s -> !s.isEmpty()).forEach(builder::pathSegment);
            }

            if (queryParamName != null && !queryParamName.isEmpty()) {
                String[] keys = queryParamName.split(",");
                String[] values = queryParamValue.split(",");
                for (int i = 0; i < keys.length; i++) {
                    builder.queryParam(keys[i], values[i]);
                }
            }

            return (T) getRestTemplate().postForObject(builder.toUriString(), setRequestHeader(requestType, mediaType), responseClass);
        } catch (Exception e) {
            throw new ApiNotAccessibleException(e);
        }
    }

    public <T> T getApi(ApiName apiName, List<String> pathsegments, String queryParamName, String queryParamValue,
                        Class<?> responseType) throws ApiNotAccessibleException {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(Objects.requireNonNull(environment.getProperty(apiName.name())));

            if (pathsegments != null) {
                pathsegments.stream().filter(Objects::nonNull).filter(s -> !s.isEmpty()).forEach(builder::pathSegment);
            }

            if (queryParamName != null && !queryParamName.isEmpty()) {
                String[] keys = queryParamName.split(",");
                String[] values = queryParamValue.split(",");
                for (int i = 0; i < keys.length; i++) {
                    builder.queryParam(keys[i], values[i]);
                }
            }

            UriComponents uriComponents = builder.build(false).encode();
            return (T) getRestTemplate().exchange(uriComponents.toUri(), HttpMethod.GET,
                    setRequestHeader(null, null), responseType).getBody();
        } catch (Exception e) {
            throw new ApiNotAccessibleException(e);
        }
    }

    public <T> T getApi(ApiName apiName, Map<String, String> pathsegments, Class<?> responseType) throws Exception {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(Objects.requireNonNull(environment.getProperty(apiName.name())));
            URI urlWithPath = builder.build(pathsegments);
            return (T) getRestTemplate().exchange(urlWithPath, HttpMethod.GET,
                    setRequestHeader(null, null), responseType).getBody();
        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    private HttpEntity<Object> setRequestHeader(Object requestType, MediaType mediaType) throws IOException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("Cookie", getToken());

        if (mediaType != null) {
            headers.add("Content-Type", mediaType.toString());
        }

        if (requestType instanceof HttpEntity<?> httpEntity) {
            HttpHeaders existingHeaders = httpEntity.getHeaders();
            existingHeaders.forEach((key, values) -> {
                if (!headers.containsKey("Content-Type") || !key.equalsIgnoreCase("Content-Type")) {
                    values.stream().findFirst().ifPresent(value -> headers.add(key, value));
                }
            });
            return new HttpEntity<>(httpEntity.getBody(), headers);
        }

        return new HttpEntity<>(requestType, headers);
    }

    public String getToken() throws IOException {
        String token = System.getProperty("token");
        boolean isValid = false;

        if (StringUtils.isNotEmpty(token)) {
            isValid = TokenHandlerUtil.isValidBearerToken(token,
                    environment.getProperty("data.share.token.request.issuerUrl"),
                    environment.getProperty("data.share.token.request.clientId"));
        }

        if (!isValid) {
            TokenRequestDTO<SecretKeyRequest> tokenRequestDTO = new TokenRequestDTO<>();
            tokenRequestDTO.setId(environment.getProperty("data.share.token.request.id"));
            tokenRequestDTO.setMetadata(new Metadata());
            tokenRequestDTO.setRequesttime(DateUtils.getUTCCurrentDateTimeString());
            tokenRequestDTO.setRequest(setSecretKeyRequestDTO());
            tokenRequestDTO.setVersion(environment.getProperty("data.share.token.request.version"));

            Gson gson = new Gson();
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost post = new HttpPost(environment.getProperty("KEYBASEDTOKENAPI"));
                post.setEntity(new StringEntity(gson.toJson(tokenRequestDTO)));
                post.setHeader("Content-type", "application/json");

                try (CloseableHttpResponse response = httpClient.execute(post)) {
                    org.apache.hc.core5.http.HttpEntity entity = response.getEntity();
                    String responseBody = EntityUtils.toString(entity);
                    Header[] cookie = response.getHeaders("Set-Cookie");
                    if (cookie.length == 0) throw new IOException("cookie is empty. Could not generate new token.");

                    token = cookie[0].getValue();
                    System.setProperty("token", token.substring(14, token.indexOf(';')));
                    return token.substring(0, token.indexOf(';'));
                }
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
        }

        return AUTHORIZATION + token;
    }

    private SecretKeyRequest setSecretKeyRequestDTO() {
        SecretKeyRequest request = new SecretKeyRequest();
        request.setAppId(environment.getProperty("data.share.token.request.appid"));
        request.setClientId(environment.getProperty("data.share.token.request.clientId"));
        request.setSecretKey(environment.getProperty("data.share.token.request.secretKey"));
        return request;
    }

    private PasswordRequest setPasswordRequestDTO() {
        PasswordRequest request = new PasswordRequest();
        request.setAppId(environment.getProperty("data.share.token.request.appid"));
        request.setPassword(environment.getProperty("data.share.token.request.password"));
        request.setUserName(environment.getProperty("data.share.token.request.username"));
        return request;
    }
}
