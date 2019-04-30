# StreetWise
The appliance code for StreetWise runs on the [speech appliance hardware](streetwise-hardware), recording audio and submitting to the StreetWise server.

## Setup
The following instructions assume that you have already assembled the [speech appliance hardware](streetwise-hardware) and deployed its SD card image.

To set up the appliance software, as in the other components of the StreetWise system, you will need to configure the server URL and API key in order to allow the appliance to submit questions and retrieve answers. Edit `SERVER_URL` and `SERVER_KEY` in [Pss2.java](src/main/java/ac/robinson/pss2/pss2.java) to configure these values.

To deploy to the StreetWise appliance, you will need to set up an Embedded Linux JVM Run Configuration. Enter the IP of your Raspberry Pi, and the username and password to use. Select `pss2_main` as the Module, and `ac.robinson.pss2.Pss2` as the Main Class. You will also need to add `-Djava.library.path=/usr/lib` to the VM Options box to ensure the correct libraries are loaded.

## License
Apache 2.0
