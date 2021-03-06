package top.tangyh.lamp.gateway.filter;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import top.tangyh.basic.base.R;
import top.tangyh.basic.cache.model.CacheKey;
import top.tangyh.basic.cache.repository.CacheOps;
import top.tangyh.basic.context.ContextConstants;
import top.tangyh.basic.context.ContextUtil;
import top.tangyh.basic.exception.BizException;
import top.tangyh.basic.exception.UnauthorizedException;
import top.tangyh.basic.jwt.TokenUtil;
import top.tangyh.basic.jwt.model.AuthInfo;
import top.tangyh.basic.jwt.utils.JwtUtil;
import top.tangyh.basic.utils.StrPool;
import top.tangyh.lamp.common.cache.common.TokenUserIdCacheKeyBuilder;
import top.tangyh.lamp.common.constant.BizConstant;
import top.tangyh.lamp.common.properties.IgnoreProperties;

import static top.tangyh.basic.context.ContextConstants.BASIC_HEADER_KEY;
import static top.tangyh.basic.context.ContextConstants.BEARER_HEADER_KEY;
import static top.tangyh.basic.context.ContextConstants.JWT_KEY_CLIENT_ID;
import static top.tangyh.basic.context.ContextConstants.JWT_KEY_SUB_TENANT;
import static top.tangyh.basic.context.ContextConstants.JWT_KEY_TENANT;
import static top.tangyh.basic.exception.code.ExceptionCode.JWT_NOT_LOGIN;
import static top.tangyh.basic.exception.code.ExceptionCode.JWT_OFFLINE;

/**
 * ?????????
 *
 * @author zuihou
 * @date 2019/07/31
 */
@Component
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties({IgnoreProperties.class})
public class TokenContextFilter implements WebFilter, Ordered {
    @Value("${spring.profiles.active:dev}")
    protected String profiles;
    @Value("${lamp.database.multiTenantType:SCHEMA}")
    protected String multiTenantType;
    private final IgnoreProperties ignoreProperties;
    private final TokenUtil tokenUtil;
    private final CacheOps cacheOps;

    protected boolean isDev(String token) {
        return !StrPool.PROD.equalsIgnoreCase(profiles) && (StrPool.TEST_TOKEN.equalsIgnoreCase(token) || StrPool.TEST.equalsIgnoreCase(token));
    }

    @Override
    public int getOrder() {
        return -1000;
    }


    /**
     * ?????? ??????token
     */
    protected boolean isIgnoreToken(String path) {
        return ignoreProperties.isIgnoreToken(path);
    }

    /**
     * ?????? ????????????
     */
    protected boolean isIgnoreTenant(String path) {
        return ignoreProperties.isIgnoreTenant(path);
    }

    protected String getHeader(String headerName, ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String token = StrUtil.EMPTY;
        if (headers == null || headers.isEmpty()) {
            return token;
        }

        token = headers.getFirst(headerName);

        if (StrUtil.isNotBlank(token)) {
            return token;
        }

        return request.getQueryParams().getFirst(headerName);
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpRequest.Builder mutate = request.mutate();

        ContextUtil.setGrayVersion(getHeader(ContextConstants.GRAY_VERSION, request));

        try {
            //1, ?????? ???????????????????????????
            parseTenant(request, mutate);

            // 2,?????? Authorization
            parseClient(request, mutate);

            // 3????????? ????????? token
            Mono<Void> token = parseToken(exchange, chain);
            if (token != null) {
                return token;
            }
        } catch (UnauthorizedException e) {
            return errorResponse(response, e.getMessage(), e.getCode(), HttpStatus.UNAUTHORIZED);
        } catch (BizException e) {
            return errorResponse(response, e.getMessage(), e.getCode(), HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            return errorResponse(response, "??????token??????", R.FAIL_CODE, HttpStatus.BAD_REQUEST);
        }

        ServerHttpRequest build = mutate.build();
        return chain.filter(exchange.mutate().request(build).build());
    }


    private Mono<Void> parseToken(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpRequest.Builder mutate = request.mutate();
        // ??????????????????????????????token??????
        if (isIgnoreToken(request.getPath().toString())) {
            log.debug("???????????????{}, ???????????????token", request.getPath().toString());
            return chain.filter(exchange);
        }

        // ?????????????????????token
        String token = getHeader(BEARER_HEADER_KEY, request);

        AuthInfo authInfo;
        // ???????????? && token=test ?????????????????????????????????????????????
        if (isDev(token)) {
            authInfo = new AuthInfo().setAccount("lamp").setUserId(2L)
                    .setTokenType(BEARER_HEADER_KEY).setName("???????????????");
        } else {
            authInfo = tokenUtil.getAuthInfo(token);

            // ?????? ??????????????????????????????????????????
            String newToken = JwtUtil.getToken(token);
            // TOKEN_USER_ID:{token} === T
            CacheKey cacheKey = new TokenUserIdCacheKeyBuilder().key(newToken);
            String tokenCache = cacheOps.get(cacheKey);

            if (StrUtil.isEmpty(tokenCache)) {
                return errorResponse(response, JWT_NOT_LOGIN.getMsg(), JWT_NOT_LOGIN.getCode(), HttpStatus.UNAUTHORIZED);
            } else if (StrUtil.equals(BizConstant.LOGIN_STATUS, tokenCache)) {
                return errorResponse(response, JWT_OFFLINE.getMsg(), JWT_OFFLINE.getCode(), HttpStatus.UNAUTHORIZED);
            }
        }

        // ??????????????????token???????????????????????????????????????????????????????????????????????????
        // ?????????????????????HeaderThreadLocalInterceptor????????????????????????????????????????????????ThreadLocal???
        if (authInfo != null) {
            addHeader(mutate, ContextConstants.JWT_KEY_ACCOUNT, authInfo.getAccount());
            addHeader(mutate, ContextConstants.JWT_KEY_USER_ID, authInfo.getUserId());
            addHeader(mutate, ContextConstants.JWT_KEY_NAME, authInfo.getName());

            MDC.put(ContextConstants.JWT_KEY_USER_ID, String.valueOf(authInfo.getUserId()));
        }
        return null;
    }

    private void parseClient(ServerHttpRequest request, ServerHttpRequest.Builder mutate) {
        String base64Authorization = getHeader(BASIC_HEADER_KEY, request);
        if (StrUtil.isNotEmpty(base64Authorization)) {
            String[] client = JwtUtil.getClient(base64Authorization);
            ContextUtil.setClientId(client[0]);
            addHeader(mutate, JWT_KEY_CLIENT_ID, ContextUtil.getClientId());
        }
    }

    private void parseTenant(ServerHttpRequest request, ServerHttpRequest.Builder mutate) {
        // NONE?????? ????????????tenant
        if ("NONE".equals(multiTenantType)) {
            addHeader(mutate, JWT_KEY_TENANT, "_NONE");
            ContextUtil.setTenant("_NONE");
            MDC.put(JWT_KEY_TENANT, StrPool.EMPTY);
            return;
        }
        // ????????????????????????????????? ????????????(tenant) ??????
        if (isIgnoreTenant(request.getPath().toString())) {
            return;
        }
        String base64Tenant = getHeader(JWT_KEY_TENANT, request);
        if (StrUtil.isNotEmpty(base64Tenant)) {
            String tenant = JwtUtil.base64Decoder(base64Tenant);

            ContextUtil.setTenant(tenant);
            addHeader(mutate, JWT_KEY_TENANT, tenant);
            MDC.put(JWT_KEY_TENANT, tenant);
        }
        String base64SubTenant = getHeader(JWT_KEY_SUB_TENANT, request);
        if (StrUtil.isNotEmpty(base64SubTenant)) {
            String subTenant = JwtUtil.base64Decoder(base64SubTenant);

            ContextUtil.setSubTenant(subTenant);
            addHeader(mutate, JWT_KEY_SUB_TENANT, subTenant);
            MDC.put(JWT_KEY_SUB_TENANT, subTenant);
        }
    }

    private void addHeader(ServerHttpRequest.Builder mutate, String name, Object value) {
        if (value == null) {
            return;
        }
        String valueStr = value.toString();
        String valueEncode = URLUtil.encode(valueStr);
        mutate.header(name, valueEncode);
    }

    protected Mono<Void> errorResponse(ServerHttpResponse response, String errMsg, int errCode, HttpStatus httpStatus) {
        R tokenError = R.fail(errCode, errMsg);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.setStatusCode(httpStatus);
        DataBuffer dataBuffer = response.bufferFactory().wrap(tokenError.toString().getBytes());
        return response.writeWith(Mono.just(dataBuffer));
    }

}
