Retrieve endpoints for an auth id.
----

* **URL**

  `/push/endpoint/:id`

* **Method:**

  `GET`

  Retrieve endpoints for the associated authId.

* **Response:**

    Example JSON response.

```json
[
  "default-platform-arn:stubbed:default-platform-arn:token-3",
  "default-platform-arn:stubbed:default-platform-arn:token-1"
]
```

*  **URL Params**

   **None:**
 
* **Success Response:**

  * **Code:** 200 OK<br />

* **Error Response:**

  * **Code:** 404 NOT_FOUND <br />
  Returned when there are no endpoints for the associated authId.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />
  On failure of the service.


