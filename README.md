# Thread-dump-visualizer

This is plugin for ItelliJ IDEA.  
The goal of this plugin is to provide handy way of analyzing thread dumps of IntelliJ IDEA.  
You can get latest version [here](https://plugins.jetbrains.com/idea/plugin/9358-threaddumpvisualizer)

To see dump's details you should drag and drop file with it to "Thread dumps" tool window.  
Following options are supported:
  * .txt file. Please note that only IDEA [format](https://github.com/JetBrains/intellij-community/blob/master/platform/util/src/com/intellij/diagnostic/ThreadDumper.java) is supported.
  Other formats are **not** supported.
  * .zip file with some proper .txt dump files
  * .dbconf file with connection information to MongoDB.  Data should be in collection named "ThreadDumps"  
  Example: {  "host" : "127.0.0.1",  "port" : 27017,  "dbName" : "test" }
  
You can drop multiple files to tool window: 

![Tool window](https://cloud.githubusercontent.com/assets/12435950/23120797/de885004-f76e-11e6-924f-d759698940a7.jpg)

Colors of circles are correspond to AWT thread state:


| Color         | State         |
| :-----------: |:-------------:|
| Green         | RUNNING       |
| Yellow        | RUNNING (but thread is yielding) |
| Red           | WAITING       |


Then you should double-click on some dump in tool window to see detailed information.  
It consist of stacktraces and diagram of thread dependencies.  

Stacktraces will be shown in new editor window.  
Useful information such as thread states, links to resolved classes, etc. will be highlighted:

![Editor](https://cloud.githubusercontent.com/assets/12435950/23121659/50cdab0c-f772-11e6-8c11-00754391d136.jpg)

Diagram shows the threads which block AWT thread:

![Diagram](https://cloud.githubusercontent.com/assets/12435950/23120893/3f0adfd2-f76f-11e6-8b35-327c6c082baa.jpg)

Diagram nodes are clickable, if you do so then editor will be focused on blocking action.
