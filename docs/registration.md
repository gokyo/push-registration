Register for Push Registration
----
  Register device details for Push Notification.

* **URL**

  `/push/register`

* **Method:**



  `POST`

    Example JSON POST payload for registration where device information is supplied. Please note the device information is optional. 


```json
{
  "token": "some-token",
  "device": {
    "os": "android",
    "version": "7.0",
    "model": "Nexus 5"
  }
}
```

The JSON structure is detailed below. 

| *Attribute* | *Description* |
|--------|----|
| ```token``` | The notification token associated with the device. The maximum size of this attribute is 1024.|
| ```device.os``` | The OS associated with the token. The valid values are ios, android or windows.  |
| ```device.version``` | The version of the OS.  The maximum size of this attribute is 50. |
| ```device.model``` | The model of the device.  The maximum size of this attribute is 100. |


*  **URL Params**

   **None:**
 
* **Success Response:**
  * **Code:** 201 - created new record <br />

  * **Code:** 200 - Updated an existing record<br />

* **Error Response:**

  * **Code:** 400 BADREQUEST <br />
    **Content:** `{"code":"BADREQUEST","message":"Bad Request"}`

  * **Code:** 401 UNAUTHORIZED <br />
    **Content:** `{"code":"UNAUTHORIZED","message":"Bearer token is missing or not authorized for access"}`

  * **Code:** 403 FORBIDDEN <br />
    **Content:** `{"code":"FORBIDDEN","message":"Access denied"}`

  * **Code:** 406 NOT ACCEPTABLE <br />
    **Content:** `{"code":"ACCEPT_HEADER_INVALID","message":"The accept header is missing or invalid"}`

  OR when the details cannot be resolved.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />


