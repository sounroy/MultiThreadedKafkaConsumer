# Multi Threaded Kafka Consumer
#The project showcases an optimal  way of writing multi threaded Kafka consumer covering possible edge cases.

#Multi threading consumers ,considering partition as unit of task and thread per partition model.

    #Taking care of tracking the offsets.
    
    #Handling partition rebalances.
    
    #etc.
    
 To run a kafka broker
  
  docker prune
  docker-compose up
  
  To visualize the kafka data 
    ![image](https://user-images.githubusercontent.com/20036322/124955945-1d09c480-e035-11eb-9ee2-59d55e8d5f17.png)

 To execute
 
 mvn clean install
 
 Run the following class
 
 Driver.java
 
 ![image](https://user-images.githubusercontent.com/20036322/124956200-60fcc980-e035-11eb-93c7-ac2c43b8b15b.png)

