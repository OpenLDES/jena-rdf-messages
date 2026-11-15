# RDF Messages for Linked Data Interactions

Components for [Linked Data Interactions (LDI)](https://github.com/OpenLDES/Linked-Data-Interactions), the OpenLDES
pipeline framework in which linked data models flow through adapters, transformers and outputs. Maven:
`org.openldes:jena-messages-ldi`, which depends on `org.openldes.ldi:ldi-api`.

* `RdfMessagesAdapter` (an `LdiAdapter`): turns the content of an RDF Message log into one `Model` per message, as soon
  as each message is complete. It is the message-aware counterpart of LDI's `RdfAdapter`, which turns a document into
  one model. A document without delimiters is one model, as before. The `version` parameter of the media type
  (`application/n-quads; version=1.2-messages`) announces messages when the body does not.
* `RdfMessageLogOutput` (an `LdiOutput`): appends every model it receives as one message to an RDF Message log (TriG by
  default), so that the end of a pipeline can be replayed with the adapter or any RDF Messages parser.

LDI pipelines carry `Model`s, which have no named graphs: the adapter hands over the union of all graphs of a message,
and the output writes models in the default graph. Wiring the components into LDIO (Spring configurators) or NiFi
processors happens in those frameworks' repositories, as for the other LDI components.
