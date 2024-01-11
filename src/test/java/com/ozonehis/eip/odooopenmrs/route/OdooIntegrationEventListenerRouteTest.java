package com.ozonehis.eip.odooopenmrs.route;

import static com.ozonehis.eip.odooopenmrs.route.OdooTestConstants.EX_PROP_ENTITY;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.openmrs.eip.mysql.watcher.WatcherConstants.PROP_EVENT;

import java.util.HashMap;
import java.util.Map;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.support.DefaultExchange;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfig;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.eip.AppContext;
import org.openmrs.eip.mysql.watcher.Event;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "odoo.custom.table.resource.mappings=obs:obs,encounter:encounter")
public class OdooIntegrationEventListenerRouteTest extends BaseOdooRouteTest {

    private static final String ROUTE_ID = "odoo-event-listener";

    @EndpointInject("mock:odoo-auth")
    private MockEndpoint mockAuthEndpoint;

    @EndpointInject("mock:odoo-entity-handler")
    private MockEndpoint mockEntityHandlerEndpoint;

    @EndpointInject("mock:odoo-patient-handler")
    private MockEndpoint mockPatientHandlerEndpoint;

    @EndpointInject("mock:odoo-patient-association-handler")
    private MockEndpoint mockPatientAssociationEndpoint;

    @EndpointInject(OdooTestConstants.URI_MOCK_GET_ENTITY_BY_UUID)
    private MockEndpoint mockFetchResourceEndpoint;

    @BeforeEach
    public void setup() throws Exception {
        loadXmlRoutesInCamelDirectory("odoo-integration-event-listener.xml");
        loadXmlRoutesInCamelDirectory("odoo-patient-association-handler.xml");

        mockFetchResourceEndpoint.reset();
        mockAuthEndpoint.reset();
        mockEntityHandlerEndpoint.reset();
        mockPatientHandlerEndpoint.reset();
        mockPatientAssociationEndpoint.reset();

        advise(ROUTE_ID, new AdviceWithRouteBuilder() {

            @Override
            public void configure() {
                interceptSendToEndpoint(OdooTestConstants.URI_ODOO_AUTH)
                        .skipSendToOriginalEndpoint()
                        .to(mockAuthEndpoint);
                interceptSendToEndpoint(OdooTestConstants.URI_GET_ENTITY_BY_UUID)
                        .skipSendToOriginalEndpoint()
                        .to(mockFetchResourceEndpoint);
                interceptSendToEndpoint("direct:odoo-entity-handler")
                        .skipSendToOriginalEndpoint()
                        .to(mockEntityHandlerEndpoint);
                interceptSendToEndpoint(OdooTestConstants.URI_PATIENT_HANDLER)
                        .skipSendToOriginalEndpoint()
                        .to(mockPatientHandlerEndpoint);
                interceptSendToEndpoint(OdooTestConstants.URI_PATIENT_ASSOCIATION_HANDLER)
                        .skipSendToOriginalEndpoint()
                        .to(mockPatientAssociationEndpoint);
            }
        });
    }

    @Test
    public void shouldSkipSnapshotEvents() {
        Event event = createEvent("orders", "1", "some-uuid", "c");
        event.setSnapshot(true);
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        assertNotNull(exchange.getProperty(OdooTestConstants.EX_PROP_TABLE_RESOURCE_MAP));
        assertNull(exchange.getProperty(EX_PROP_ENTITY));
    }

    @Test
    public void shouldSkipNonMonitoredTables() {
        Event event = createEvent("visit", "1", "some_uuid", "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        assertNotNull(exchange.getProperty(OdooTestConstants.EX_PROP_TABLE_RESOURCE_MAP));
        assertNull(exchange.getProperty(EX_PROP_ENTITY));
    }

    @Test
    public void shouldInvokeTheConfiguredObsHandler() throws Exception {
        final String obsUuid = "obs_uuid";
        Event event = createEvent("obs", "1", obsUuid, "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);
        var obsResource = new HashMap<>();
        obsResource.put("uuid", obsUuid);
        mockFetchResourceEndpoint.expectedMessageCount(1);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_IS_SUBRESOURCE, false);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_RESOURCE_NAME, "obs");
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_RESOURCE_ID, obsUuid);
        final String obsJson = mapper.writeValueAsString(obsResource);
        mockFetchResourceEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(obsJson));
        mockEntityHandlerEndpoint.expectedMessageCount(1);

        mockEntityHandlerEndpoint.reset();
        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        mockFetchResourceEndpoint.assertIsSatisfied();
        mockEntityHandlerEndpoint.assertIsSatisfied();
        var map = exchange.getProperty(OdooTestConstants.EX_PROP_TABLE_RESOURCE_MAP, Map.class);
        assertNotNull(map);
        assertEquals(6, map.size());
        assertEquals("patient", map.get("patient"));
        assertEquals("name", map.get("person_name"));
        assertEquals("address", map.get("person_address"));
        assertEquals("identifier", map.get("patient_identifier"));
        assertEquals("obs", map.get("obs"));
        assertEquals("encounter", map.get("encounter"));
    }

    @Test
    public void shouldProcessAnEventForAPatient() throws Exception {
        Event event = createEvent("patient", "5", OdooTestConstants.PATIENT_UUID, "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);
        mockAuthEndpoint.expectedMessageCount(1);
        mockPatientHandlerEndpoint.expectedMessageCount(1);
        mockEntityHandlerEndpoint.expectedMessageCount(0);
        mockPatientAssociationEndpoint.expectedMessageCount(0);
        mockPatientAssociationEndpoint.expectedMessageCount(0);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_IS_SUBRESOURCE, false);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_RESOURCE_NAME, "patient");
        mockFetchResourceEndpoint.expectedPropertyReceived(
                OdooTestConstants.EX_PROP_RESOURCE_ID, OdooTestConstants.PATIENT_UUID);
        var patientResource = singletonMap("uuid", OdooTestConstants.PATIENT_UUID);
        final String patientJson = mapper.writeValueAsString(patientResource);
        mockFetchResourceEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(patientJson));

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        mockFetchResourceEndpoint.assertIsSatisfied();
        mockAuthEndpoint.assertIsSatisfied();
        mockEntityHandlerEndpoint.assertIsSatisfied();
        mockPatientHandlerEndpoint.assertIsSatisfied();
        mockPatientAssociationEndpoint.assertIsSatisfied();
        assertEquals(patientResource, exchange.getProperty(EX_PROP_ENTITY));
        assertNotNull(exchange.getProperty(OdooTestConstants.EX_PROP_TABLE_RESOURCE_MAP));
    }

    @Test
    public void shouldUseTheCachedOdooUserIdAndNotAuthenticateWithOdoo() throws Exception {
        final int odooUserid = 9;
        AppContext.add(OdooTestConstants.ODOO_USER_ID_KEY, odooUserid);
        Event event = createEvent("patient", "5", OdooTestConstants.PATIENT_UUID, "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);
        mockAuthEndpoint.expectedMessageCount(0);
        mockEntityHandlerEndpoint.expectedMessageCount(0);
        mockPatientAssociationEndpoint.expectedMessageCount(0);
        mockPatientAssociationEndpoint.expectedMessageCount(0);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_IS_SUBRESOURCE, false);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_RESOURCE_NAME, "patient");
        mockFetchResourceEndpoint.expectedPropertyReceived(
                OdooTestConstants.EX_PROP_RESOURCE_ID, OdooTestConstants.PATIENT_UUID);
        var patientResource = new HashMap<>();
        patientResource.put("uuid", OdooTestConstants.PATIENT_UUID);
        mockPatientHandlerEndpoint.expectedMessageCount(1);
        mockPatientHandlerEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_PATIENT, patientResource);
        final String patientJson = mapper.writeValueAsString(patientResource);
        mockFetchResourceEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(patientJson));

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        mockFetchResourceEndpoint.assertIsSatisfied();
        mockAuthEndpoint.assertIsSatisfied();
        mockEntityHandlerEndpoint.assertIsSatisfied();
        mockPatientHandlerEndpoint.assertIsSatisfied();
        mockPatientAssociationEndpoint.assertIsSatisfied();
        assertEquals(patientResource, exchange.getProperty(EX_PROP_ENTITY));
        assertEquals(
                OdooTestConstants.ODOO_USER_ID_KEY, exchange.getProperty(OdooTestConstants.EX_PROP_ODOO_USER_ID_KEY));
        assertEquals(odooUserid, AppContext.get(OdooTestConstants.ODOO_USER_ID_KEY));
        assertNotNull(exchange.getProperty(OdooTestConstants.EX_PROP_TABLE_RESOURCE_MAP));
    }

    @Test
    public void shouldAuthenticateWithOdooIfNoOdooUserIdIsCached() throws Exception {
        Event event = createEvent("patient", "5", OdooTestConstants.PATIENT_UUID, "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);
        mockEntityHandlerEndpoint.expectedMessageCount(0);
        mockPatientAssociationEndpoint.expectedMessageCount(0);
        mockPatientAssociationEndpoint.expectedMessageCount(0);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_IS_SUBRESOURCE, false);
        mockFetchResourceEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_RESOURCE_NAME, "patient");
        mockFetchResourceEndpoint.expectedPropertyReceived(
                OdooTestConstants.EX_PROP_RESOURCE_ID, OdooTestConstants.PATIENT_UUID);
        var patientResource = new HashMap<>();
        patientResource.put("uuid", OdooTestConstants.PATIENT_UUID);
        mockPatientHandlerEndpoint.expectedMessageCount(1);
        mockPatientHandlerEndpoint.expectedPropertyReceived(OdooTestConstants.EX_PROP_PATIENT, patientResource);
        final String patientJson = mapper.writeValueAsString(patientResource);
        mockFetchResourceEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(patientJson));
        mockAuthEndpoint.expectedMessageCount(1);
        final int odooUserid = 8;
        mockAuthEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(odooUserid));

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        mockFetchResourceEndpoint.assertIsSatisfied();
        mockAuthEndpoint.assertIsSatisfied();
        mockEntityHandlerEndpoint.assertIsSatisfied();
        mockPatientHandlerEndpoint.assertIsSatisfied();
        mockPatientAssociationEndpoint.assertIsSatisfied();
        assertEquals(patientResource, exchange.getProperty(EX_PROP_ENTITY));
        assertEquals(
                OdooTestConstants.ODOO_USER_ID_KEY, exchange.getProperty(OdooTestConstants.EX_PROP_ODOO_USER_ID_KEY));
        assertEquals(odooUserid, AppContext.get(OdooTestConstants.ODOO_USER_ID_KEY));
        assertNotNull(exchange.getProperty(OdooTestConstants.EX_PROP_TABLE_RESOURCE_MAP));
    }

    @Test
    public void shouldCreateAndCacheTheXmlRpcClientAndConfig() throws Exception {
        assertNull(AppContext.get(OdooTestConstants.RPC_CLIENT_KEY));
        assertNull(AppContext.get(OdooTestConstants.RPC_CONFIG_KEY));
        final String obsUuid = "obs_uuid";
        Event event = createEvent("obs", "1", obsUuid, "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);
        var obsResource = new HashMap<>();
        obsResource.put("uuid", obsUuid);
        final String obsJson = mapper.writeValueAsString(obsResource);
        mockFetchResourceEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(obsJson));

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        assertNotNull(AppContext.get(OdooTestConstants.RPC_CLIENT_KEY));
        assertNotNull(AppContext.get(OdooTestConstants.RPC_CONFIG_KEY));
    }

    @Test
    public void shouldNotCreateNewXmlRpcClientAndConfigIfTheCacheAlreadyContainsOne() throws Exception {
        XmlRpcClient client = new XmlRpcClient();
        XmlRpcClientConfig config = new XmlRpcClientConfigImpl();
        AppContext.add(OdooTestConstants.RPC_CLIENT_KEY, client);
        AppContext.add(OdooTestConstants.RPC_CONFIG_KEY, config);
        final String obsUuid = "obs_uuid";
        Event event = createEvent("obs", "1", obsUuid, "c");
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty(PROP_EVENT, event);
        var obsResource = new HashMap<>();
        obsResource.put("uuid", obsUuid);
        final String obsJson = mapper.writeValueAsString(obsResource);
        mockFetchResourceEndpoint.whenAnyExchangeReceived(e -> e.getIn().setBody(obsJson));

        producerTemplate.send(OdooTestConstants.LISTENER_URI, exchange);

        assertEquals(client, AppContext.get(OdooTestConstants.RPC_CLIENT_KEY));
        assertEquals(config, AppContext.get(OdooTestConstants.RPC_CONFIG_KEY));
    }
}
