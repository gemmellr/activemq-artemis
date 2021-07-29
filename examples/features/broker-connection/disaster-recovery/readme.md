# AMQP Broker Disaster REcovery
 
To run the example, simply type **mvn verify** from this directory, or **mvn -PnoServer verify** if you want to create and start the broker manually.
 
On this broker you will have two brokers connected to each other.

Whatever happens on the first broker is mirrored to the second broker, and vice versa.

It is a dual mirror configuration.

(TODO: I should make this text here better before the final PR ^^^ )