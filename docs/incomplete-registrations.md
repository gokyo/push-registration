Fetch incomplete registrations
----

* **URL**

  `/push/endpoint`

* **Method:**

  `GET`

  Fetch incomplete registrations, that is, push registration records that do not currently have an associated ARN endpoint.  
  
  Note that each record will only be returned once, allowing multiple threads to receive 'unique' records, and to process registrations in parallel.

* **Response:**

    Example JSON response.

```json
[
  {
    "token": "token_3",
    "device": {
      "os": "windows",
      "osVersion": "1.0",
      "appVersion": "1.0",
      "model": "Baz"
    }
  },
  {
    "token": "token_2",
    "device": {
      "os": "ios",
      "osVersion": "1.0",
      "appVersion": "1.0",
      "model": "Bar"
    }
  },
  {
    "token": "token_1",
    "device": {
      "os": "android",
      "osVersion": "1.0",
      "appVersion": "1.0",
      "model": "Foo"
    }
  }
]
```

*  **URL Params**

   **None:**
 
* **Success Response:**
  * **Code:** 200 OK<br />

* **Error Response:**

  * **Code:** 404 NOT_FOUND <br />
  Returned when there are no push registration records that do not have an AWS endpoint and that have not already been fetched previously.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />
  On failure of the service.

