
deployment(name:'ServiceLogEvent Test') {

    artifact id:'service', 'org.rioproject.test.simple:simple-logging-service:2.0'
    artifact id:'service-dl', 'org.rioproject.test.simple:simple-api:2.0'

    groups '${org.rioproject.groups}'

    service(name: 'Simple Logging Simon') {
        interfaces {
            classes 'org.rioproject.test.simple.Simple'
             artifact ref:'service-dl'
        }
        implementation(class: 'org.rioproject.test.simple.LoggingSimpleImpl') {
             artifact ref:'service'
        }

        maintain 1
    }
}