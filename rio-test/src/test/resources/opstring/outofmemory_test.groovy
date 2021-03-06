package opstring
import org.rioproject.config.Constants

def String getCodebase() {
    return System.getProperty(Constants.WEBSTER)
}

deployment(name:'Out Of Memory') {

    codebase getCodebase()

    groups System.getProperty('org.rioproject.groups')

    service(name: 'OOME', fork:'yes') {
        interfaces {
            classes 'org.rioproject.test.memory.OutOfMemory'
            resources 'test-classes/'
        }
        implementation(class: 'org.rioproject.test.memory.OutOfMemoryServiceImpl') {
            resources 'test-classes/'
        }

        //faultDetectionHandler HeartbeatFaultDetectionHandler.class.getName()
        
        maintain 1
    }
}
